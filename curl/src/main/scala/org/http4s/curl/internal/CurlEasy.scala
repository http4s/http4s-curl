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
import cats.effect.Resource
import org.http4s.curl.CurlError
import org.http4s.curl.unsafe.libcurl._
import org.http4s.curl.unsafe.libcurl_const._

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

final private[curl] class CurlEasy private (val curl: Ptr[CURL], errBuffer: Ptr[CChar]) {

  @inline private def throwOnError(thunk: => CURLcode): Unit = {
    val code = thunk
    if (code.isError) {
      val details = fromCString(errBuffer)
      throw CurlError.fromCode(code, details)
    }
  }

  def setUrl(URL: Ptr[CChar]): Unit = throwOnError(curl_easy_setopt_url(curl, CURLOPT_URL, URL))

  def setCustomRequest(request: Ptr[CChar]): Unit =
    throwOnError(curl_easy_setopt_customrequest(curl, CURLOPT_CUSTOMREQUEST, request))

  def setHttpHeader(
      headers: Ptr[curl_slist]
  ): Unit = throwOnError(curl_easy_setopt_httpheader(curl, CURLOPT_HTTPHEADER, headers))

  def setHttpVersion(
      version: CLong
  ): Unit = throwOnError(curl_easy_setopt_http_version(curl, CURLOPT_HTTP_VERSION, version))

  def setHeaderFunction(
      header_callback: header_callback
  ): Unit = throwOnError(
    curl_easy_setopt_headerfunction(curl, CURLOPT_HEADERFUNCTION, header_callback)
  )

  def setHeaderData(
      pointer: Ptr[Byte]
  ): Unit = throwOnError(curl_easy_setopt_headerdata(curl, CURLOPT_HEADERDATA, pointer))

  def setWriteFunction(
      write_callback: write_callback
  ): Unit = throwOnError(
    curl_easy_setopt_writefunction(curl, CURLOPT_WRITEFUNCTION, write_callback)
  )

  def setWriteData(
      pointer: Ptr[Byte]
  ): Unit = throwOnError(curl_easy_setopt_writedata(curl, CURLOPT_WRITEDATA, pointer))

  def setReadFunction(
      read_callback: read_callback
  ): Unit = throwOnError(curl_easy_setopt_readfunction(curl, CURLOPT_READFUNCTION, read_callback))

  def setReadData(
      pointer: Ptr[Byte]
  ): Unit = throwOnError(curl_easy_setopt_readdata(curl, CURLOPT_READDATA, pointer))

  def setUpload(value: Boolean): Unit =
    throwOnError(curl_easy_setopt_upload(curl, CURLOPT_UPLOAD, if (value) 1 else 0))

  def setVerbose(value: Boolean): Unit =
    throwOnError(curl_easy_setopt_verbose(curl, CURLOPT_VERBOSE, if (value) 1 else 0))

  def setWebsocket(
      flags: CLong
  ): Unit = throwOnError(curl_easy_setopt_websocket(curl, CURLOPT_WS_OPTIONS, flags))

  def wsSend(
      buffer: Ptr[Byte],
      bufLen: CSize,
      send: Ptr[CSize],
      fragsize: CSize,
      flags: UInt,
  ): Unit = throwOnError(curl_easy_ws_send(curl, buffer, bufLen, send, fragsize, flags))

  def wsMeta(): Ptr[curl_ws_frame] = curl_easy_ws_meta(curl)

  def pause(bitmask: CInt): Unit = throwOnError(curl_easy_pause(curl, bitmask))
}

private[curl] object CurlEasy {
  private final val CURL_ERROR_SIZE = 256L

  private val createHandler: Resource[IO, Ptr[CURL]] = Resource.make {
    IO {
      val handle = curl_easy_init()
      if (handle == null)
        throw new RuntimeException("curl_easy_init")
      handle
    }
  } { handle =>
    IO(curl_easy_cleanup(handle))
  }

  def apply(): Resource[IO, CurlEasy] =
    for {
      h <- createHandler
      z <- Utils.newZone
      buf = z.alloc(CURL_ERROR_SIZE.toULong)
      _ <- Resource.eval(IO {
        val code = curl_easy_setopt_errorbuffer(h, CURLOPT_ERRORBUFFER, buf)
        if (code.isError) {
          throw CurlError.fromCode(code)
        }
      })
    } yield new CurlEasy(h, buf)
}
