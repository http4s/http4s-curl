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
import cats.effect.unsafe.PollingExecutorScheduler
import org.http4s.curl.CurlError

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

final private[curl] class CurlExecutorScheduler(multiHandle: Ptr[libcurl.CURLM], pollEvery: Int)
    extends PollingExecutorScheduler(pollEvery) {

  private val callbacks = mutable.Map[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit]()

  def poll(timeout: Duration): Boolean = {
    val timeoutIsInf = timeout == Duration.Inf
    val noCallbacks = callbacks.isEmpty

    if (timeoutIsInf && noCallbacks) false
    else {
      val timeoutMillis =
        if (timeoutIsInf) Int.MaxValue else timeout.toMillis.min(Int.MaxValue).toInt

      if (timeout > Duration.Zero) {

        val pollCode = libcurl.curl_multi_poll(
          multiHandle,
          null,
          0.toUInt,
          timeoutMillis,
          null,
        )

        if (pollCode.isError)
          throw CurlError.fromMCode(pollCode)
      }

      if (noCallbacks) false
      else {
        val runningHandles = stackalloc[CInt]()
        val performCode = libcurl.curl_multi_perform(multiHandle, runningHandles)

        if (performCode.isError)
          throw CurlError.fromMCode(performCode)

        while ({
          val msgsInQueue = stackalloc[CInt]()
          val info = libcurl.curl_multi_info_read(multiHandle, msgsInQueue)

          if (info != null) {
            val curMsg = libcurl.curl_CURLMsg_msg(info)
            if (curMsg == libcurl_const.CURLMSG_DONE) {
              val handle = libcurl.curl_CURLMsg_easy_handle(info)
              callbacks.remove(handle).foreach { cb =>
                val result = libcurl.curl_CURLMsg_data_result(info)
                cb(
                  if (result.isOk) Right(())
                  else Left(CurlError.fromCode(result))
                )
              }

              val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
              if (code.isError)
                throw CurlError.fromMCode(code)
            }
            true
          } else false
        }) {}

        !runningHandles > 0
      }
    }
  }

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
  def addHandle(handle: Ptr[libcurl.CURL], cb: Either[Throwable, Unit] => Unit): Unit = {
    val code = libcurl.curl_multi_add_handle(multiHandle, handle)
    if (code.isError)
      throw CurlError.fromMCode(code)
    callbacks(handle) = cb
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
  ): Resource[IO, Unit] = Resource.make(IO(addHandle(handle, cb)))(_ =>
    IO(callbacks.remove(handle).foreach(_(Right(()))))
  )
}

private[curl] object CurlExecutorScheduler {

  def apply(pollEvery: Int): (CurlExecutorScheduler, () => Unit) = {
    val initCode = libcurl.curl_global_init(2)
    if (initCode.isError)
      throw CurlError.fromCode(initCode)

    val multiHandle = libcurl.curl_multi_init()
    if (multiHandle == null)
      throw new RuntimeException("curl_multi_init")

    val shutdown = () => {
      val code = libcurl.curl_multi_cleanup(multiHandle)
      libcurl.curl_global_cleanup()
      if (code.isError)
        throw CurlError.fromMCode(code)
    }

    (new CurlExecutorScheduler(multiHandle, pollEvery), shutdown)
  }

}
