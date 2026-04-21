/*
 * Copyright 2022 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.curl.unsafe

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.unsafe.PollResult
import cats.effect.unsafe.PollingContext
import cats.effect.unsafe.PollingSystem
import cats.effect.unsafe.metrics.PollerMetrics
import org.http4s.curl.CurlError

import scala.collection.mutable
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

object CurlPollingSystem extends PollingSystem {
  type Api = CurlApi
  type Poller = CurlPoller

  // Global one-time initialization (NOT thread-safe, must happen once)
  private val initCode = libcurl.curl_global_init(libcurl_const.CURL_GLOBAL_DEFAULT)
  if (initCode.isError)
    throw CurlError.fromCode(initCode)

  def makePoller(): CurlPoller = {
    val multiHandle = libcurl.curl_multi_init()
    if (multiHandle == null)
      throw new RuntimeException("curl_multi_init")
    new CurlPoller(multiHandle)
  }

  def makeApi(ctx: PollingContext[CurlPoller]): CurlApi = new CurlApi(ctx)

  def closePoller(poller: CurlPoller): Unit = {
    val code = libcurl.curl_multi_cleanup(poller.multiHandle)
    if (code.isError)
      throw CurlError.fromMCode(code)
  }

  /** Polls for curl I/O activity. Only discovers completed transfers and buffers
    * them — does NOT fire completion callbacks. Callbacks are fired in
    * [[processReadyEvents]] which the WSTP calls separately, allowing the worker
    * thread to execute rescheduled fibers between calls.
    */
  def poll(poller: CurlPoller, nanos: Long): PollResult = {
    val timeoutMillis =
      if (nanos < 0) Int.MaxValue
      else (nanos / 1000000L).min(Int.MaxValue.toLong).toInt

    val noCallbacks = poller.callbacks.isEmpty

    if (timeoutMillis == 0 && noCallbacks) PollResult.Interrupted
    else {
      val pollCode = libcurl.curl_multi_poll(
        poller.multiHandle,
        null,
        0.toUInt,
        math.max(timeoutMillis, 0),
        null,
      )

      if (pollCode.isError)
        throw CurlError.fromMCode(pollCode)

      if (noCallbacks) PollResult.Interrupted
      else {
        val runningHandles = stackalloc[CInt]()
        val performCode = libcurl.curl_multi_perform(poller.multiHandle, runningHandles)

        if (performCode.isError)
          throw CurlError.fromMCode(performCode)

        // Collect completed transfers into the buffer — do NOT fire callbacks here
        while ({
          val msgsInQueue = stackalloc[CInt]()
          val info = libcurl.curl_multi_info_read(poller.multiHandle, msgsInQueue)

          if (info != null) {
            val curMsg = libcurl.curl_CURLMsg_msg(info)
            if (curMsg == libcurl_const.CURLMSG_DONE) {
              val handle = libcurl.curl_CURLMsg_easy_handle(info)
              val result = libcurl.curl_CURLMsg_data_result(info)
              poller.completedBuffers += ((handle, result))

              val code = libcurl.curl_multi_remove_handle(poller.multiHandle, handle)
              if (code.isError)
                throw CurlError.fromMCode(code)
            }
            true
          } else false
        }) ()

        if (poller.completedBuffers.nonEmpty) PollResult.Complete
        else PollResult.Interrupted
      }
    }
  }

  /** Fires completion callbacks for transfers that completed during [[poll]].
    * This is called by the WSTP worker thread after poll() returns, giving the
    * worker a chance to execute rescheduled fibers between iterations of the
    * drainReadyEvents loop.
    */
  def processReadyEvents(poller: CurlPoller): Boolean =
    if (poller.completedBuffers.isEmpty) false
    else {
      var rescheduled = false
      while (poller.completedBuffers.nonEmpty) {
        val (handle, result) = poller.completedBuffers.remove(0)
        poller.callbacks.remove(handle).foreach { cb =>
          cb(if (result.isOk) Right(()) else Left(CurlError.fromCode(result)))
          rescheduled = true
        }
      }
      rescheduled
    }

  def needsPoll(poller: CurlPoller): Boolean =
    poller.callbacks.nonEmpty || poller.completedBuffers.nonEmpty

  def interrupt(targetThread: Thread, targetPoller: CurlPoller): Unit = {
    val code = libcurl.curl_multi_wakeup(targetPoller.multiHandle)
    if (code.isError)
      throw CurlError.fromMCode(code)
  }

  def metrics(poller: CurlPoller): PollerMetrics = poller

  def close(): Unit = libcurl.curl_global_cleanup()
}

final class CurlPoller(val multiHandle: Ptr[libcurl.CURLM]) extends PollerMetrics {
  val callbacks: scala.collection.concurrent.TrieMap[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit] =
    scala.collection.concurrent.TrieMap.empty
  val completedBuffers: mutable.ArrayDeque[(Ptr[libcurl.CURL], libcurl.CURLcode)] =
    mutable.ArrayDeque.empty

  override def toString: String = "CurlPoller"

  // PollerMetrics — all return 0 (matches current behavior)
  def operationsOutstandingCount(): Int = 0
  def totalOperationsSubmittedCount(): Long = 0
  def totalOperationsSucceededCount(): Long = 0
  def totalOperationsErroredCount(): Long = 0
  def totalOperationsCanceledCount(): Long = 0
  def acceptOperationsOutstandingCount(): Int = 0
  def totalAcceptOperationsSubmittedCount(): Long = 0
  def totalAcceptOperationsSucceededCount(): Long = 0
  def totalAcceptOperationsErroredCount(): Long = 0
  def totalAcceptOperationsCanceledCount(): Long = 0
  def connectOperationsOutstandingCount(): Int = 0
  def totalConnectOperationsSubmittedCount(): Long = 0
  def totalConnectOperationsSucceededCount(): Long = 0
  def totalConnectOperationsErroredCount(): Long = 0
  def totalConnectOperationsCanceledCount(): Long = 0
  def readOperationsOutstandingCount(): Int = 0
  def totalReadOperationsSubmittedCount(): Long = 0
  def totalReadOperationsSucceededCount(): Long = 0
  def totalReadOperationsErroredCount(): Long = 0
  def totalReadOperationsCanceledCount(): Long = 0
  def writeOperationsOutstandingCount(): Int = 0
  def totalWriteOperationsSubmittedCount(): Long = 0
  def totalWriteOperationsSucceededCount(): Long = 0
  def totalWriteOperationsErroredCount(): Long = 0
  def totalWriteOperationsCanceledCount(): Long = 0
}

final class CurlApi private[curl] (
    private val ctx: PollingContext[CurlPoller],
) {

  /** Adds a curl handler that is expected to terminate
    * like a normal http request
    *
    * IMPORTANT NOTE: if you add a transfer that does not terminate (e.g. websocket) using this method,
    * application might hang, because those transfer don't seem to change state,
    * so it's not distinguishable whether they are finished or have other work to do
    *
    * @param handle curl easy handle to add
    * @param cb callback to run when this handler has finished its transfer
    */
  def addHandle(handle: Ptr[libcurl.CURL], cb: Either[Throwable, Unit] => Unit): Unit =
    ctx.accessPoller { poller =>
      poller.callbacks(handle) = cb
      val code = libcurl.curl_multi_add_handle(poller.multiHandle, handle)
      if (code.isError) {
        poller.callbacks.remove(handle)
        throw CurlError.fromMCode(code)
      }
      val wakeupCode = libcurl.curl_multi_wakeup(poller.multiHandle)
      if (wakeupCode.isError)
        throw CurlError.fromMCode(wakeupCode)
    }

  /** Add a curl handle for a transfer that doesn't finish e.g. a websocket transfer
    * it adds a handle to multi handle, and removes it when it goes out of scope
    * so no dangling handler will remain in multi handler
    * callback is called when the transfer is terminated or goes out of scope
    *
    * @param handle curl easy handle to add
    * @param cb callback to run if this handler is terminated unexpectedly
    */
  def addHandleR(
      handle: Ptr[libcurl.CURL],
      cb: Either[Throwable, Unit] => Unit,
  ): Resource[IO, Unit] = {
    var owningPoller: CurlPoller = null
    Resource.make(
      IO {
        ctx.accessPoller { poller =>
          owningPoller = poller
          poller.callbacks(handle) = cb
          val code = libcurl.curl_multi_add_handle(poller.multiHandle, handle)
          if (code.isError) {
            poller.callbacks.remove(handle)
            throw CurlError.fromMCode(code)
          }
          val wakeupCode = libcurl.curl_multi_wakeup(poller.multiHandle)
          if (wakeupCode.isError)
            throw CurlError.fromMCode(wakeupCode)
        }
      }
    ) { _ =>
      IO {
        if (owningPoller != null) {
          val _ = owningPoller.callbacks.remove(handle)
          val code = libcurl.curl_multi_remove_handle(owningPoller.multiHandle, handle)
          if (code.isError)
            throw CurlError.fromMCode(code)
        }
      }
    }
  }
}
