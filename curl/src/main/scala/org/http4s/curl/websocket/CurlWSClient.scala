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
import org.http4s.client.websocket.WSFrame._
import org.http4s.client.websocket._
import org.http4s.curl.unsafe.CurlExecutorScheduler
import org.http4s.curl.unsafe.CurlMultiSocket
import org.http4s.curl.unsafe.CurlRuntime
import org.http4s.curl.unsafe.libcurl_const
import scodec.bits.ByteVector

private[curl] object CurlWSClient {

  // TODO change to builder
  def get(
      recvBufferSize: Int = 100,
      pauseOn: Int = 10,
      resumeOn: Int = 30,
      verbose: Boolean = false,
  ): IO[WSClient[IO]] = IO.executionContext.flatMap {
    case ec: CurlExecutorScheduler =>
      IO.fromOption(apply(ec, recvBufferSize, pauseOn, resumeOn, verbose))(
        new RuntimeException("websocket client is not supported in this environment")
      )
    case _ => IO.raiseError(new RuntimeException("Not running on CurlExecutorScheduler"))
  }

  def apply(
      ec: CurlExecutorScheduler,
      recvBufferSize: Int,
      pauseOn: Int,
      resumeOn: Int,
      verbose: Boolean,
  ): Option[WSClient[IO]] =
    Option.when(CurlRuntime.isWebsocketAvailable && CurlRuntime.curlVersionNumber >= 0x75700) {
      WSClient(true) { req =>
        Connection(req, ec, recvBufferSize, pauseOn, resumeOn, verbose)
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

              override def receive: IO[Option[WSFrame]] = con.receive

              override def subprotocol: Option[String] = None

            }
          )
      }
    }

  def apply(
      ms: CurlMultiSocket,
      recvBufferSize: Int = 100,
      pauseOn: Int = 10,
      resumeOn: Int = 30,
      verbose: Boolean = false,
  ): Option[WSClient[IO]] =
    Option.when(CurlRuntime.isWebsocketAvailable && CurlRuntime.curlVersionNumber >= 0x75700) {
      WSClient(true) { req =>
        Connection(req, ms, recvBufferSize, pauseOn, resumeOn, verbose)
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

              override def receive: IO[Option[WSFrame]] = con.receive

              override def subprotocol: Option[String] = None

            }
          )
      }
    }
}
