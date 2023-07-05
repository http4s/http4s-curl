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
import cats.effect.unsafe.PollingSystem
import org.http4s.curl.internal.CurlEasy
import org.http4s.curl.internal.CurlMulti
import org.http4s.curl.internal.CurlMultiDriver

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

final private[curl] class CurlMultiPerformPoller(
    multiHandle: CurlMulti
) extends PollingSystem
    with CurlMultiDriver {

  private[this] var needsPoll = true

  type Api = CurlMultiDriver
  type Poller = CurlMultiPerformPoller

  override def addHandlerTerminating(
      easy: CurlEasy,
      cb: Either[Throwable, Unit] => Unit,
  ): IO[Unit] = IO(multiHandle.addHandle(easy.curl, cb))

  override def addHandlerNonTerminating(
      easy: CurlEasy,
      cb: Either[Throwable, Unit] => Unit,
  ): Resource[IO, Unit] =
    Resource.make(addHandlerTerminating(easy, cb))(_ => IO(multiHandle.removeHandle(easy.curl)))

  override def close(): Unit = ()

  override def makeApi(register: (Poller => Unit) => Unit): Api = this

  override def makePoller(): Poller = this

  override def closePoller(poller: Poller): Unit = {
    multiHandle.clean
    libcurl.curl_global_cleanup()
  }

  override def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean = {
    val timeoutIsInf = nanos == -1
    val noCallbacks = multiHandle.noCallbacks

    if (timeoutIsInf && noCallbacks) false
    else {
      val timeoutMillis =
        if (timeoutIsInf) Int.MaxValue else (nanos / 1e6).toInt

      if (nanos > 0) {

        libcurl
          .curl_multi_poll(
            multiHandle.multiHandle,
            null,
            0.toUInt,
            timeoutMillis,
            null,
          )
          .throwOnError
      }

      if (noCallbacks) false
      else {
        val runningHandles = stackalloc[CInt]()
        libcurl.curl_multi_perform(multiHandle.multiHandle, runningHandles).throwOnError

        multiHandle.onTick

        needsPoll = !runningHandles > 0
        true
      }
    }

  }

  override def needsPoll(poller: Poller): Boolean = needsPoll

  override def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

}

private[curl] object CurlMultiPerformPoller {
  def apply(): CurlMultiPerformPoller = new CurlMultiPerformPoller(CurlMulti.unsafe())
}
