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

package org.http4s.curl.internal

import cats.effect.IO
import cats.effect.kernel.Resource
import org.http4s.curl.CurlError
import org.http4s.curl.unsafe.libcurl
import org.http4s.curl.unsafe.libcurl_const

import scala.collection.mutable
import scala.scalanative.unsafe.Ptr
import scala.scalanative.unsafe._

final private[curl] class CurlMulti private (val multiHandle: Ptr[libcurl.CURLM]) {
  private val callbacks = mutable.Map[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit]()

  /** Adds a curl handler to the multi handle
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

  /** Removes a handle and notifies success
    *
    * @param handle curl easy handle to add
    */
  def removeHandle(
      handle: Ptr[libcurl.CURL]
  ): Unit = callbacks.remove(handle).foreach(_(Right(())))

  def noCallbacks: Boolean = callbacks.isEmpty

  /** Event handler */
  def onTick: Unit =
    if (noCallbacks) ()
    else {
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
    }

  /** Clears callbacks and notifies all waiting fibers */
  def clearCallbacks: Unit = {
    val error = new InterruptedException("Runtime shutdown!")
    callbacks.foreach { case (easy, cb) =>
      libcurl.curl_multi_remove_handle(multiHandle, easy).throwOnError
      cb(Left(error))
    }
    callbacks.clear()
  }

  def clean: Unit = {
    clearCallbacks

    libcurl.curl_multi_cleanup(multiHandle).throwOnError
  }
}

object CurlMulti {

  /** global init must be called exactly once */
  private lazy val setup: Unit = libcurl.curl_global_init(2).throwOnError

  private def newCurlMutli: Ptr[libcurl.CURLM] = {
    setup
    val multiHandle = libcurl.curl_multi_init()
    if (multiHandle == null)
      throw new RuntimeException("curl_multi_init")
    multiHandle
  }

  def apply(): Resource[IO, CurlMulti] = Resource.make(IO(unsafe()))(m => IO(m.clean))
  def unsafe(): CurlMulti = new CurlMulti(newCurlMutli)
}
