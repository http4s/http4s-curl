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

package org.http4s.curl.websocket

import cats.Foldable
import cats.effect.IO
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.websocket.WSFrame._
import org.http4s.client.websocket._
import org.http4s.curl.internal.Utils
import org.http4s.curl.internal.Utils.throwOnError
import org.http4s.curl.unsafe.CurlExecutorScheduler
import org.http4s.curl.unsafe.CurlRuntime
import org.http4s.curl.unsafe.libcurl
import org.http4s.curl.unsafe.libcurl_const
import scodec.bits.ByteVector

import scala.annotation.unused
import scala.scalanative.unsafe._

private[curl] object CurlWSClient {

  def get(recvBufferSize: Int = 100): IO[Option[WSClient[IO]]] = IO.executionContext.flatMap {
    case ec: CurlExecutorScheduler => IO.pure(apply(ec, recvBufferSize))
    case _ => IO.raiseError(InvalidRuntime)
  }

  final private val ws = Uri.Scheme.unsafeFromString("ws")
  final private val wss = Uri.Scheme.unsafeFromString("wss")

  private def setup(req: WSRequest, ec: CurlExecutorScheduler)(
      con: Connection
  )(implicit zone: Zone) = IO {
    val scheme = req.uri.scheme.getOrElse(ws)

    if (scheme != ws && scheme != wss)
      throw new RuntimeException(s"Websocket client can't handle ${scheme.value} scheme!")

    val uri = req.uri.copy(scheme = Some(scheme))

    throwOnError(
      libcurl.curl_easy_setopt_customrequest(
        con.handler,
        libcurl_const.CURLOPT_CUSTOMREQUEST,
        toCString(req.method.renderString),
      )
    )

    // NOTE raw mode in curl needs decoding metadata in client side
    // which is not implemented! so this client never receives a ping
    // as all pings are always handled by libcurl itself
    // throwOnError(
    //   libcurl.curl_easy_setopt_websocket(
    //     con.handler,
    //     libcurl_const.CURLOPT_WS_OPTIONS,
    //     libcurl_const.CURLWS_RAW_MODE,
    //   )
    // )

    throwOnError(
      libcurl.curl_easy_setopt_url(
        con.handler,
        libcurl_const.CURLOPT_URL,
        toCString(uri.renderString),
      )
    )

    // NOTE there is no need to handle object lifetime here,
    // as Connection class and curl handler have the same lifetime
    throwOnError {
      libcurl.curl_easy_setopt_writedata(
        con.handler,
        libcurl_const.CURLOPT_WRITEDATA,
        Utils.toPtr(con),
      )
    }

    throwOnError {
      libcurl.curl_easy_setopt_writefunction(
        con.handler,
        libcurl_const.CURLOPT_WRITEFUNCTION,
        recvCallback(_, _, _, _),
      )
    }

    libcurl.curl_easy_setopt_headerdata(
      con.handler,
      libcurl_const.CURLOPT_HEADERDATA,
      Utils.toPtr(con),
    )

    throwOnError {
      libcurl.curl_easy_setopt_headerfunction(
        con.handler,
        libcurl_const.CURLOPT_HEADERFUNCTION,
        headerCallback(_, _, _, _),
      )
    }

    var headers: Ptr[libcurl.curl_slist] = null
    req.headers
      .foreach { header =>
        headers = libcurl.curl_slist_append(headers, toCString(header.toString))
      }
    throwOnError(
      libcurl.curl_easy_setopt_httpheader(con.handler, libcurl_const.CURLOPT_HTTPHEADER, headers)
    )

    ec.addHandle(con.handler, con.onTerminated)
  }

  /** libcurl write callback */
  private def recvCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
      userdata: Ptr[Byte],
  ): CSize =
    Utils
      .fromPtr[Connection](userdata)
      .onReceive(buffer, size, nmemb)

  private def headerCallback(
      @unused buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte],
  ): CSize = {
    Utils
      .fromPtr[Connection](userdata)
      .onEstablished()

    size * nitems
  }

  def apply(
      ec: CurlExecutorScheduler,
      recvBufferSize: Int = 10,
  ): Option[WSClient[IO]] =
    Option.when(CurlRuntime.isWebsocketAvailable && CurlRuntime.curlVersionNumber >= 0x75700) {
      WSClient(true) { req =>
        Utils.newZone
          .flatMap(implicit zone =>
            Connection(recvBufferSize)
              .evalTap(setup(req, ec))
          )
          .map(con =>
            new WSConnection[IO] {

              override def send(wsf: WSFrame): IO[Unit] = wsf match {
                case Close(_, _) =>
                  val flags = libcurl_const.CURLWS_CLOSE
                  con.send(flags, ByteVector.empty)
                case Ping(data) =>
                  val flags = libcurl_const.CURLWS_PING
                  con.send(flags, data)
                case Pong(data) =>
                  val flags = libcurl_const.CURLWS_PONG
                  con.send(flags, data)
                case Text(data, true) =>
                  val flags = libcurl_const.CURLWS_TEXT
                  val bv =
                    ByteVector.encodeUtf8(data).getOrElse(throw InvalidTextFrame)
                  con.send(flags, bv)
                case Binary(data, true) =>
                  val flags = libcurl_const.CURLWS_BINARY
                  con.send(flags, data)
                case _ =>
                  // NOTE curl needs to know total amount of fragment size in first send
                  // and it is not compatible with current websocket interface in http4s
                  IO.raiseError(PartialFragmentFrame)
              }

              override def sendMany[G[_]: Foldable, A <: WSFrame](wsfs: G[A]): IO[Unit] =
                wsfs.traverse_(send)

              override def receive: IO[Option[WSFrame]] = con.received.take

              override def subprotocol: Option[String] = None

            }
          )
      }
    }
}
