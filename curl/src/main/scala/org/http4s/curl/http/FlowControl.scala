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
import org.http4s.curl.internal.CurlEasy
import org.http4s.curl.unsafe.libcurl_const

final private[curl] class FlowControl private (
    curl: CurlEasy,
    recvPause: Ref[SyncIO, Boolean],
    sendPause: Ref[SyncIO, Boolean],
) {
  def unpauseRecv: IO[Unit] = recvPause
    .getAndSet(false)
    .to[IO]
    .ifM( // needs unpause
      sendPause.get.to[IO].flatMap { p =>
        IO(
          curl
            .pause(if (p) libcurl_const.CURLPAUSE_SEND else libcurl_const.CURLPAUSE_SEND_CONT)
        )
      },
      IO.unit, // already unpaused
    )

  def onRecvPaused: SyncIO[Unit] = recvPause.set(true)
  def onSendPaused: SyncIO[Unit] = sendPause.set(true)

  def unpauseSend: IO[Unit] = sendPause.set(false).to[IO] *>
    recvPause.get.to[IO].flatMap { p =>
      IO(
        curl
          .pause(if (p) libcurl_const.CURLPAUSE_RECV else libcurl_const.CURLPAUSE_RECV_CONT)
      )
    }
}

private[curl] object FlowControl {
  def apply(curl: CurlEasy): Resource[IO, FlowControl] = for {
    recvPause <- Ref[SyncIO].of(false).to[IO].toResource
    sendPause <- Ref[SyncIO].of(false).to[IO].toResource
  } yield new FlowControl(curl, recvPause, sendPause)
}
