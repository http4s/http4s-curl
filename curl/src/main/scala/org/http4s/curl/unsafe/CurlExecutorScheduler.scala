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
import cats.effect.unsafe.PollingContext
import cats.effect.unsafe.PollingSystem
import cats.effect.unsafe.PollResult
import cats.effect.unsafe.metrics.PollerMetrics
import org.http4s.curl.CurlError

import scala.collection.mutable
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

final class CurlPoller(val multiHandle: Ptr[libcurl.CURLM]) extends PollerMetrics {
  val callbacks: mutable.HashMap[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit] =
    mutable.HashMap.empty

  var processing: Boolean = false

  def operationsOutstandingCount(): Int = 0
  def readOperationsOutstandingCount(): Int = 0
  def writeOperationsOutstandingCount(): Int = 0
  def connectOperationsOutstandingCount(): Int = 0
  def acceptOperationsOutstandingCount(): Int = 0
  def totalOperationsSubmittedCount(): Long = 0L
  def totalOperationsSucceededCount(): Long = 0L
  def totalOperationsErroredCount(): Long = 0L
  def totalOperationsCanceledCount(): Long = 0L
  def totalReadOperationsSubmittedCount(): Long = 0L
  def totalReadOperationsSucceededCount(): Long = 0L
  def totalReadOperationsErroredCount(): Long = 0L
  def totalReadOperationsCanceledCount(): Long = 0L
  def totalWriteOperationsSubmittedCount(): Long = 0L
  def totalWriteOperationsSucceededCount(): Long = 0L
  def totalWriteOperationsErroredCount(): Long = 0L
  def totalWriteOperationsCanceledCount(): Long = 0L
  def totalConnectOperationsSubmittedCount(): Long = 0L
  def totalConnectOperationsSucceededCount(): Long = 0L
  def totalConnectOperationsErroredCount(): Long = 0L
  def totalConnectOperationsCanceledCount(): Long = 0L
  def totalAcceptOperationsSubmittedCount(): Long = 0L
  def totalAcceptOperationsSucceededCount(): Long = 0L
  def totalAcceptOperationsErroredCount(): Long = 0L
  def totalAcceptOperationsCanceledCount(): Long = 0L
}

final class CurlApi(ctx: PollingContext[CurlPoller]) {

  def addHandle(handle: Ptr[libcurl.CURL], cb: Either[Throwable, Unit] => Unit): Unit = {
    ctx.accessPoller { poller =>
      poller.callbacks(handle) = cb
      val code = libcurl.curl_multi_add_handle(poller.multiHandle, handle)
      if (code.isError) {
        poller.callbacks.remove(handle)
        throw CurlError.fromMCode(code)
      }
    }
  }

  def addHandleR(
      handle: Ptr[libcurl.CURL],
      cb: Either[Throwable, Unit] => Unit,
  ): Resource[IO, Unit] = {
    var owningPoller: CurlPoller = null
    Resource.make(
      IO(ctx.accessPoller { poller =>
        owningPoller = poller
        poller.callbacks(handle) = cb
        val code = libcurl.curl_multi_add_handle(poller.multiHandle, handle)
        if (code.isError) {
          poller.callbacks.remove(handle)
          throw CurlError.fromMCode(code)
        }
      })
    ) { _ =>
      IO {
        if (owningPoller != null) {
          owningPoller.callbacks.remove(handle).foreach(_(Right(())))
        }
      }
    }
  }
}

final class CurlPollingSystem extends PollingSystem {
  type Poller = CurlPoller
  type Api = CurlApi

  locally {
    val initCode = libcurl.curl_global_init(libcurl_const.CURL_GLOBAL_DEFAULT)
    if (initCode.isError) throw CurlError.fromCode(initCode)
  }

  def close(): Unit =
    libcurl.curl_global_cleanup()

  def makePoller(): CurlPoller = {
    val mh = libcurl.curl_multi_init()
    if (mh == null) throw new RuntimeException("curl_multi_init failed")
    new CurlPoller(mh)
  }

  def closePoller(poller: CurlPoller): Unit = {
    val code = libcurl.curl_multi_cleanup(poller.multiHandle)
    if (code.isError) throw CurlError.fromMCode(code)
  }

  def makeApi(ctx: PollingContext[CurlPoller]): CurlApi =
    new CurlApi(ctx)

  def poll(poller: CurlPoller, nanos: Long): PollResult = {
    if (nanos == 0L) {
      // Non-blocking: check if we have pending callbacks
      if (poller.callbacks.nonEmpty) PollResult.Complete
      else PollResult.Interrupted
    } else {
      // Blocking: wait for activity via curl_multi_poll
      val timeoutMillis =
        if (nanos < 0L) Int.MaxValue
        else {
          val ms = nanos / 1000000L
          if (ms > Int.MaxValue.toLong) Int.MaxValue else ms.toInt
        }

      // Wait for activity; return code is intentionally ignored —
      // processReadyEvents drives transfers and handles results
      libcurl.curl_multi_poll(poller.multiHandle, null, 0.toUInt, timeoutMillis, null)
      PollResult.Complete
    }
  }

  def processReadyEvents(poller: CurlPoller): Boolean = {
    // Prevent re-entrancy: callbacks fired by curl_multi_perform can cause
    // fiber rescheduling that re-enters this method on the same worker thread.
    if (poller.processing) return false
    poller.processing = true
    try {
      // Drive all transfers
      val runningHandles = stackalloc[CInt]()
      val performCode = libcurl.curl_multi_perform(poller.multiHandle, runningHandles)
      // CURLM_RECURSIVE_API_CALL (8): curl detected a re-entrant call triggered
      // by callback-induced fiber rescheduling. No data is lost — defer to the
      // next polling iteration.
      if (performCode.isError && performCode.value != 8) throw CurlError.fromMCode(
        performCode
      )

      // Drain completed transfers and fire callbacks
      drainCompletedTransfers(poller)
    } finally {
      poller.processing = false
    }
  }

  def needsPoll(poller: CurlPoller): Boolean =
    !poller.callbacks.isEmpty

  def interrupt(targetThread: Thread, targetPoller: CurlPoller): Unit = {
    val code = libcurl.curl_multi_wakeup(targetPoller.multiHandle)
    if (code.isError) throw CurlError.fromMCode(code)
  }

  def metrics(poller: CurlPoller): PollerMetrics = poller

  private def drainCompletedTransfers(poller: CurlPoller): Boolean = {
    var fired = false
    val msgsInQueue = stackalloc[CInt]()
    var info = libcurl.curl_multi_info_read(poller.multiHandle, msgsInQueue)
    while (info != null) {
      val curMsg = libcurl.curl_CURLMsg_msg(info)
      if (curMsg == libcurl_const.CURLMSG_DONE) {
        val handle = libcurl.curl_CURLMsg_easy_handle(info)
        poller.callbacks.remove(handle).foreach { cb =>
          val result = libcurl.curl_CURLMsg_data_result(info)
          cb(if (result.isOk) Right(()) else Left(CurlError.fromCode(result)))
          fired = true
        }
        val code = libcurl.curl_multi_remove_handle(poller.multiHandle, handle)
        if (code.isError) throw CurlError.fromMCode(code)
      }
      info = libcurl.curl_multi_info_read(poller.multiHandle, msgsInQueue)
    }
    fired
  }
}
