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
import java.util.concurrent.ConcurrentHashMap

object CurlPollingSystem extends PollingSystem {
  type Api = CurlApi
  type Poller = CurlPoller

  private val initCode = libcurl.curl_global_init(libcurl_const.CURL_GLOBAL_DEFAULT)
  if (initCode.isError)
    throw CurlError.fromCode(initCode)

  private val multiHandle = libcurl.curl_multi_init()
  if (multiHandle == null)
    throw new RuntimeException("curl_multi_init")

  private val callbacks =
    new ConcurrentHashMap[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit]
  private val primaryPoller = new java.util.concurrent.atomic.AtomicReference[CurlPoller](null)

  def makePoller(): CurlPoller = {
    val poller = new CurlPoller(multiHandle, callbacks)
    primaryPoller.compareAndSet(null, poller)
    poller
  }

  def makeApi(ctx: PollingContext[CurlPoller]): CurlApi = new CurlApi(ctx, this)

  def closePoller(poller: CurlPoller): Unit = ()

  /** Polls for curl I/O activity. Only discovers completed transfers and buffers
    * them — does NOT fire completion callbacks. Callbacks are fired in
    * [[processReadyEvents]] which the WSTP calls separately, allowing the worker
    * thread to execute rescheduled fibers between calls.
    */
  def poll(poller: CurlPoller, nanos: Long): PollResult = {
    if (poller ne primaryPoller.get()) return PollResult.Interrupted

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
              poller.completedBuffers += ((handle, result.isOk, result))

              val code = libcurl.curl_multi_remove_handle(poller.multiHandle, handle)
              if (code.isError)
                throw CurlError.fromMCode(code)
            }
            true
          } else false
        }) ()

        val result =
          if (poller.completedBuffers.nonEmpty)
            PollResult.Complete
          else
            PollResult.Interrupted

        result
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
        val (handle, isOk, result) = poller.completedBuffers.remove(0)
        val cb = poller.callbacks.remove(handle)
        if (cb != null) {
          cb(if (isOk) Right(()) else Left(CurlError.fromCode(result)))
          rescheduled = true
        }
      }
      rescheduled
    }

  def needsPoll(poller: CurlPoller): Boolean =
    (poller eq primaryPoller.get()) &&
      (!poller.callbacks.isEmpty || poller.completedBuffers.nonEmpty)

  def interrupt(targetThread: Thread, targetPoller: CurlPoller): Unit = {
    libcurl.curl_multi_wakeup(multiHandle)
    ()
  }

  def metrics(poller: CurlPoller): PollerMetrics = CurlPollerMetrics

  def close(): Unit = {
    val code = libcurl.curl_multi_cleanup(multiHandle)
    libcurl.curl_global_cleanup()
    if (code.isError)
      throw CurlError.fromMCode(code)
  }

  private[curl] def addHandle(
      handle: Ptr[libcurl.CURL],
      cb: Either[Throwable, Unit] => Unit,
  ): Unit = {
    callbacks.put(handle, cb)
    val code = libcurl.curl_multi_add_handle(multiHandle, handle)
    if (code.isError) {
      callbacks.remove(handle)
      throw CurlError.fromMCode(code)
    }
    val _ = libcurl.curl_multi_wakeup(multiHandle)
  }

  private[curl] def removeHandle(handle: Ptr[libcurl.CURL]): Unit = {
    val cb = callbacks.remove(handle)
    if (cb != null) cb(Right(()))
  }

}

private object CurlPollerMetrics extends PollerMetrics {
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

final class CurlPoller(
    val multiHandle: Ptr[libcurl.CURLM],
    val callbacks: ConcurrentHashMap[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit],
) {
  val completedBuffers: mutable.ArrayDeque[(Ptr[libcurl.CURL], Boolean, libcurl.CURLcode)] =
    mutable.ArrayDeque.empty
}

final class CurlApi private[curl] (
    private val ctx: PollingContext[CurlPoller],
    private val system: CurlPollingSystem.type,
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
    system.addHandle(handle, cb)

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
  ): Resource[IO, Unit] =
    Resource.make(IO(addHandle(handle, cb)))(_ => IO(system.removeHandle(handle)))
}
