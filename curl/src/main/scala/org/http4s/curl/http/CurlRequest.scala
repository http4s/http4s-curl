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

package org.http4s.curl.http

import cats.effect._
import org.http4s.Request
import org.http4s.Response
import org.http4s.curl.internal.Utils
import org.http4s.curl.internal._
import org.http4s.curl.unsafe.CurlExecutorScheduler

private[curl] object CurlRequest {
  private def setup(
      handle: CurlEasy,
      ec: CurlExecutorScheduler,
      send: RequestSend,
      recv: RequestRecv,
      req: Request[IO],
  ): Resource[IO, Unit] = Utils.newZone.flatMap(implicit zone =>
    CurlSList().evalMap(headers =>
      IO {
        // TODO add in options
        // handle.setVerbose(true)

        import org.http4s.curl.unsafe.libcurl_const
        import scala.scalanative.unsafe._
        import org.http4s.Header
        import org.http4s.HttpVersion
        import org.typelevel.ci._

        handle.setCustomRequest(toCString(req.method.renderString))

        handle.setUpload(true)

        handle.setUrl(toCString(req.uri.renderString))

        val httpVersion = req.httpVersion match {
          case HttpVersion.`HTTP/1.0` => libcurl_const.CURL_HTTP_VERSION_1_0
          case HttpVersion.`HTTP/1.1` => libcurl_const.CURL_HTTP_VERSION_1_1
          case HttpVersion.`HTTP/2` => libcurl_const.CURL_HTTP_VERSION_2
          case HttpVersion.`HTTP/3` => libcurl_const.CURL_HTTP_VERSION_3
          case _ => libcurl_const.CURL_HTTP_VERSION_NONE
        }
        handle.setHttpVersion(httpVersion)

        req.headers // curl adds these headers automatically, so we explicitly disable them
          .transform(Header.Raw(ci"Expect", "") :: Header.Raw(ci"Transfer-Encoding", "") :: _)
          .foreach(header => headers.append(header.toString))

        handle.setHttpHeader(headers.toPtr)

        // NOTE that pointers are to data that is handled by GC
        // and have proper lifetime management
        // so no need to handle them explicitly anymore
        handle.setReadData(Utils.toPtr(send))
        handle.setReadFunction(RequestSend.readCallback(_, _, _, _))

        handle.setHeaderData(Utils.toPtr(recv))
        handle.setHeaderFunction(RequestRecv.headerCallback(_, _, _, _))

        handle.setWriteData(Utils.toPtr(recv))
        handle.setWriteFunction(RequestRecv.writeCallback(_, _, _, _))

        ec.addHandle(handle.curl, recv.onTerminated)
      }
    )
  )

  def apply(ec: CurlExecutorScheduler, req: Request[IO]): Resource[IO, Response[IO]] = for {
    handle <- CurlEasy()
    flow <- FlowControl(handle)
    send <- RequestSend(flow)
    recv <- RequestRecv(flow)
    _ <- setup(handle, ec, send, recv, req)
    _ <- req.body.through(send.pipe).compile.drain.background
    resp <- recv.response()
  } yield resp
}
