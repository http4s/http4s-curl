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

import cats.effect.unsafe.PollingExecutorScheduler

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

final private[curl] class CurlExecutorScheduler(multiHandle: Ptr[libcurl.CURLM])
    extends PollingExecutorScheduler {

  private val callbacks = mutable.Map[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit]()

  def poll(timeout: Duration): Boolean = {

    if (timeout > Duration.Zero) {
      val pollCode = libcurl.curl_multi_poll(
        multiHandle,
        null,
        0.toUInt,
        timeout.toMillis.min(Int.MaxValue).toInt,
        null,
      )

      if (pollCode != 0)
        throw new RuntimeException(s"curl_multi_poll: $pollCode")
    }

    val runningHandles = stackalloc[CInt]()
    val performCode = libcurl.curl_multi_perform(multiHandle, runningHandles)
    if (performCode != 0)
      throw new RuntimeException(s"curl_multi_perform: $performCode")

    while ({
      val info = libcurl.curl_multi_info_read(multiHandle, null)
      if (info != null) {
        if (info._1 == libcurl.CURLMSG_DONE) {
          callbacks.remove(info._2).foreach { cb =>
            cb(
              if (info._3 == 0) Right(())
              else Left(new RuntimeException(s"curl_multi_info_read: ${info._3}"))
            )
          }

          val code = libcurl.curl_multi_remove_handle(multiHandle, info._2)
          if (code != 0)
            throw new RuntimeException(s"curl_multi_remove_handle: $code")
        }
        true
      } else false
    }) {}

    !runningHandles > 0
  }

  def addHandle(handle: Ptr[libcurl.CURL], cb: Either[Throwable, Unit] => Unit): Unit = {
    val code = libcurl.curl_multi_add_handle(multiHandle, handle)
    if (code != 0)
      throw new RuntimeException(s"curl_multi_add_handle: $code")
    callbacks(handle) = cb
  }
}

private[curl] object CurlExecutorScheduler {

  def apply(): (CurlExecutorScheduler, () => Unit) = {
    val initCode = libcurl.curl_global_init(0)
    if (initCode == 0)
      throw new RuntimeException(s"curl_global_init: $initCode")

    val multiHandle = libcurl.curl_multi_init()
    if (multiHandle == null)
      throw new RuntimeException("curl_multi_init")

    val shutdown = () => {
      val code = libcurl.curl_multi_cleanup(multiHandle)
      libcurl.curl_global_cleanup()
      if (code != 0)
        throw new RuntimeException(s"curl_multi_cleanup: $code")
    }

    (new CurlExecutorScheduler(multiHandle), shutdown)
  }

}
