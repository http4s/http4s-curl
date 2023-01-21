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

package org.http4s.curl

import cats.Foldable
import cats.effect.IO
import cats.effect.Resource
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.websocket.WSFrame._
import org.http4s.client.websocket._
import org.http4s.curl.unsafe.CurlExecutorScheduler
import org.http4s.curl.unsafe.CurlRuntime
import org.http4s.curl.unsafe.libcurl
import org.http4s.curl.unsafe.libcurl_const
import scodec.bits.ByteVector

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import internal.Utils.throwOnError

private[curl] object WebSocketClient {

  def get(respondToPings: Boolean = true): IO[Option[WSClient[IO]]] = IO.executionContext.flatMap {
    case ec: CurlExecutorScheduler => IO.pure(apply(ec, respondToPings))
    case _ => IO.raiseError(new RuntimeException("Not running on CurlExecutorScheduler"))
  }

  private val createHandler = Resource.make {
    IO {
      val handle = libcurl.curl_easy_init()
      if (handle == null)
        throw new RuntimeException("curl_easy_init")
      handle
    }
  } { handle =>
    IO(libcurl.curl_easy_cleanup(handle))
  }

  final private val ws = Uri.Scheme.unsafeFromString("ws")
  final private val wss = Uri.Scheme.unsafeFromString("wss")

  private def setup(req: WSRequest, ec: CurlExecutorScheduler)(
      handle: Ptr[libcurl.CURL]
  )(implicit zone: Zone) =
    IO {
      println("start")
      val scheme = req.uri.scheme.getOrElse(ws)

      if (scheme != ws && scheme != wss)
        throw new RuntimeException(s"Websocket client can't handle ${scheme.value} scheme!")

      val uri = req.uri.copy(scheme = Some(scheme))

      throwOnError(
        libcurl.curl_easy_setopt_customrequest(
          handle,
          libcurl_const.CURLOPT_CUSTOMREQUEST,
          toCString(req.method.renderString),
        )
      )

      throwOnError(
        libcurl.curl_easy_setopt_connect_only(
          handle,
          libcurl_const.CURLOPT_CONNECT_ONLY,
          2, // 2L is needed in order to work without callbacks
        )
      )

      throwOnError(
        libcurl.curl_easy_setopt_websocket(
          handle,
          libcurl_const.CURLOPT_WS_OPTIONS,
          libcurl_const.CURLWS_RAW_MODE,
        )
      )

      throwOnError(
        libcurl.curl_easy_setopt_url(
          handle,
          libcurl_const.CURLOPT_URL,
          toCString(uri.renderString),
        )
      )

      var headers: Ptr[libcurl.curl_slist] = null
      req.headers
        .foreach { header =>
          headers = libcurl.curl_slist_append(headers, toCString(header.toString))
        }
      throwOnError(
        libcurl.curl_easy_setopt_httpheader(handle, libcurl_const.CURLOPT_HTTPHEADER, headers)
      )

      ec.addHandle(handle, println)
    }

  implicit private class FrameMetaOps(private val meta: Ptr[libcurl.curl_ws_frame]) extends AnyVal {
    @inline def flags: CInt = !meta.at2
    @inline def isFinal: Boolean = (flags & libcurl_const.CURLWS_CONT) != 0
    @inline def isText: Boolean = (flags & libcurl_const.CURLWS_TEXT) != 0
    @inline def isBinary: Boolean = (flags & libcurl_const.CURLWS_BINARY) != 0
    @inline def isPing: Boolean = (flags & libcurl_const.CURLWS_PING) != 0
    @inline def isClose: Boolean = (flags & libcurl_const.CURLWS_CLOSE) != 0
  }

  def apply(ec: CurlExecutorScheduler, respondToPings: Boolean): Option[WSClient[IO]] =
    Option.when(CurlRuntime.isWebsocketAvailable) {
      WSClient(respondToPings) { req =>
        internal.Utils.newZone
          .flatMap(implicit zone =>
            createHandler
              .evalTap(setup(req, ec))
          )
          .map(curl =>
            new WSConnection[IO] {

              private def send(flags: CInt, data: ByteVector) =
                internal.Utils.newZone.use { implicit zone =>
                  IO {
                    val sent = stackalloc[CSize]()
                    val buffer = data.toPtr
                    val size = data.size.toULong

                    throwOnError(
                      libcurl.curl_easy_ws_send(curl, buffer, size, sent, size, flags.toUInt)
                    )
                  }
                }

              override def send(wsf: WSFrame): IO[Unit] = wsf match {
                case Close(_, _) =>
                  val flags = libcurl_const.CURLWS_CLOSE
                  send(flags, ByteVector.empty)
                case Ping(data) =>
                  val flags = libcurl_const.CURLWS_PING
                  send(flags, data)
                case Pong(data) =>
                  val flags = libcurl_const.CURLWS_PONG
                  send(flags, data)
                case Text(data, true) =>
                  val flags = libcurl_const.CURLWS_TEXT
                  val bv =
                    ByteVector.encodeUtf8(data).getOrElse(throw InvalidTextFrame)
                  send(flags, bv)
                case Binary(data, true) =>
                  val flags = libcurl_const.CURLWS_BINARY
                  send(flags, data)
                case _ =>
                  // NOTE curl needs to know total amount of fragment size in first send
                  // and it is not compatible with current websocket interface in http4s
                  IO.raiseError(PartialFragmentFrame)
              }

              override def sendMany[G[_]: Foldable, A <: WSFrame](wsfs: G[A]): IO[Unit] =
                wsfs.traverse_(send)

              override def receive: IO[Option[WSFrame]] = IO {
                val bufSize = 1024.toULong
                val buffer = stackalloc[Byte](bufSize)
                val recv = stackalloc[CSize]()
                val meta = stackalloc[libcurl.curl_ws_frame]()
                var payload = ByteVector.empty

                while ({
                  throwOnError(
                    libcurl.curl_easy_ws_recv(curl, buffer, bufSize, recv, meta)
                  )

                  payload = payload ++ ByteVector.fromPtr(buffer, (!recv).toLong)
                  (!recv) == bufSize
                }) {}

                if (meta.isText) {
                  val str = payload.decodeUtf8.getOrElse(throw InvalidTextFrame)
                  Some(WSFrame.Text(str, meta.isFinal))
                } else if (meta.isBinary) {
                  Some(WSFrame.Binary(payload, meta.isFinal))
                } else if (meta.isPing) {
                  Some(WSFrame.Ping(payload))
                } else if (meta.isClose) {
                  Some(
                    WSFrame.Close(1, "")
                  ) // TODO what is a sane default? or where are the required data?
                } else {
                  None
                }
              }

              override def subprotocol: Option[String] = None

            }
          )
      }
    }

  sealed trait Error extends Throwable with Serializable with Product
  case object InvalidTextFrame extends Exception("Text frame data must be valid utf8") with Error
  case object PartialFragmentFrame
      extends Exception("Partial fragments are not supported by this driver")

}
