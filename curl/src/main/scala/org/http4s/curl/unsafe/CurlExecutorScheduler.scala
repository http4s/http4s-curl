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
import cats.effect.unsafe.Scheduler
import org.http4s.curl.CurlError

import java.util.ArrayDeque
import java.util.PriorityQueue
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.util.control.NonFatal

final class CurlExecutorScheduler(
    private[this] val multiHandle: Ptr[libcurl.CURLM],
    private[this] val pollEvery: Int,
) extends ExecutionContextExecutor
    with Scheduler {

  private[this] var needsReschedule: Boolean = true
  private[this] val executeQueue: ArrayDeque[Runnable] = new ArrayDeque
  private[this] val sleepQueue: PriorityQueue[SleepTask] = new PriorityQueue
  private[this] val callbacks: mutable.Map[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit] =
    mutable.Map.empty
  private[this] val noop: Runnable = () => ()

  // ExecutionContext
  def execute(runnable: Runnable): Unit = {
    executeQueue.add(runnable)
    scheduleIfNeeded()
  }

  def reportFailure(t: Throwable): Unit =
    t.printStackTrace()

  // Scheduler
  def sleep(delay: FiniteDuration, task: Runnable): Runnable =
    if (delay <= Duration.Zero) {
      executeQueue.add(task)
      scheduleIfNeeded()
      noop
    } else {
      val sleepTask = new SleepTask(monotonicNanos() + delay.toNanos, task)
      sleepQueue.add(sleepTask)
      scheduleIfNeeded()
      sleepTask
    }

  def nowMillis(): Long = System.currentTimeMillis()
  def monotonicNanos(): Long = System.nanoTime()

  private[this] def scheduleIfNeeded(): Unit =
    if (needsReschedule) {
      ExecutionContext.global.execute(() => loop())
      needsReschedule = false
    }

  private[this] def loop(): Unit = {
    needsReschedule = false
    var continue = true

    while (continue) {
      // 1. Fire expired timers
      val now = monotonicNanos()
      while (!sleepQueue.isEmpty && sleepQueue.peek().at <= now) {
        val task = sleepQueue.poll()
        try task.runnable.run()
        catch {
          case t if NonFatal(t) => reportFailure(t)
          case t: Throwable =>
            t.printStackTrace()
            sys.exit(1)
        }
      }

      // 2. Execute fiber batch (up to pollEvery)
      var i = 0
      while (i < pollEvery && !executeQueue.isEmpty) {
        val runnable = executeQueue.poll()
        try runnable.run()
        catch {
          case t if NonFatal(t) => reportFailure(t)
          case t: Throwable =>
            t.printStackTrace()
            sys.exit(1)
        }
        i += 1
      }

      // 3. Calculate timeout
      val timeout =
        if (!executeQueue.isEmpty) Duration.Zero
        else if (!sleepQueue.isEmpty)
          math.max(sleepQueue.peek().at - monotonicNanos(), 0L).nanos
        else Duration.Inf

      val noCallbacks = callbacks.isEmpty
      val timeoutIsInf = timeout == Duration.Inf

      // 4. If nothing to do, exit loop
      if (timeoutIsInf && noCallbacks) {
        continue = false
      } else {
        val timeoutMillis =
          if (timeoutIsInf) Int.MaxValue
          else timeout.toMillis.min(Int.MaxValue.toLong).toInt

        // curl_multi_poll (blocking) if timeout > 0
        if (timeout > Duration.Zero) {
          val pollCode = libcurl.curl_multi_poll(multiHandle, null, 0.toUInt, timeoutMillis, null)
          if (pollCode.isError) throw CurlError.fromMCode(pollCode)
        }

        if (!noCallbacks) {
          // curl_multi_perform
          val runningHandles = stackalloc[CInt]()
          val performCode = libcurl.curl_multi_perform(multiHandle, runningHandles)
          if (performCode.isError) throw CurlError.fromMCode(performCode)

          // Drain completed transfers
          while ({
            val msgsInQueue = stackalloc[CInt]()
            val info = libcurl.curl_multi_info_read(multiHandle, msgsInQueue)
            if (info != null) {
              val curMsg = libcurl.curl_CURLMsg_msg(info)
              if (curMsg == libcurl_const.CURLMSG_DONE) {
                val handle = libcurl.curl_CURLMsg_easy_handle(info)
                callbacks.remove(handle).foreach { cb =>
                  val result = libcurl.curl_CURLMsg_data_result(info)
                  cb(if (result.isOk) Right(()) else Left(CurlError.fromCode(result)))
                }
                val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
                if (code.isError) throw CurlError.fromMCode(code)
              }
              true
            } else false
          }) ()
        }

        // 5. Check continue
        continue = !callbacks.isEmpty || !executeQueue.isEmpty || !sleepQueue.isEmpty
      }
    }
    needsReschedule = true
  }

  /** Adds a curl handle to the multi handle for I/O monitoring.
    * The callback is invoked when the transfer completes (success or failure).
    */
  def addHandle(handle: Ptr[libcurl.CURL], cb: Either[Throwable, Unit] => Unit): Unit = {
    val code = libcurl.curl_multi_add_handle(multiHandle, handle)
    if (code.isError) throw CurlError.fromMCode(code)
    callbacks(handle) = cb
  }

  /** Adds a curl handle as a managed resource. The handle is removed when the
    * resource is released, firing the callback with Right(()) on cleanup.
    */
  def addHandleR(
      handle: Ptr[libcurl.CURL],
      cb: Either[Throwable, Unit] => Unit,
  ): Resource[IO, Unit] =
    Resource.make(IO(addHandle(handle, cb))) { _ =>
      IO(callbacks.remove(handle).foreach(_(Right(()))))
    }

  final private[this] class SleepTask(val at: Long, val runnable: Runnable)
      extends Runnable
      with Comparable[SleepTask] {
    def run(): Unit = { sleepQueue.remove(this); () }
    def compareTo(that: SleepTask): Int = java.lang.Long.compare(this.at, that.at)
  }
}

private[curl] object CurlExecutorScheduler {
  def apply(pollEvery: Int): (CurlExecutorScheduler, () => Unit) = {
    val initCode = libcurl.curl_global_init(libcurl_const.CURL_GLOBAL_DEFAULT)
    if (initCode.isError) throw CurlError.fromCode(initCode)

    val multiHandle = libcurl.curl_multi_init()
    if (multiHandle == null) throw new RuntimeException("curl_multi_init")

    val shutdown = () => {
      val code = libcurl.curl_multi_cleanup(multiHandle)
      libcurl.curl_global_cleanup()
      if (code.isError) throw CurlError.fromMCode(code)
    }

    (new CurlExecutorScheduler(multiHandle, pollEvery), shutdown)
  }
}
