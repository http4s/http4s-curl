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
import cats.effect.implicits._
import cats.effect.std._
import fs2.Pipe
import org.http4s.curl.internal.Utils
import org.http4s.curl.unsafe.libcurl_const
import scodec.bits.ByteVector

import scalanative.unsafe._
import scalanative.unsigned._

final private[curl] class RequestSend private (
    flowControl: FlowControl,
    requestBodyChunk: Ref[SyncIO, Option[ByteVector]],
    requestBodyQueue: Queue[IO, Unit],
    sendPause: Ref[SyncIO, Boolean],
    dispatcher: Dispatcher[IO],
) {
  def pipe: Pipe[IO, Byte, Nothing] = _.chunks
    .map(_.toByteVector)
    .noneTerminate
    .foreach { chunk =>
      requestBodyQueue.take *> requestBodyChunk.set(chunk).to[IO] *> flowControl.unpauseSend
    }

  def onRead(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
  ): CSize =
    requestBodyChunk
      .modify {
        case Some(bytes) =>
          val (left, right) = bytes.splitAt((size * nitems).toLong)
          (Some(right), Some(left))
        case None => (None, None)
      }
      .unsafeRunSync() match {
      case Some(bytes) if bytes.nonEmpty =>
        bytes.copyToPtr(buffer, 0)
        bytes.length.toULong
      case Some(_) =>
        dispatcher.unsafeRunAndForget(
          sendPause.set(true).to[IO] *> requestBodyQueue.offer(())
        )
        libcurl_const.CURL_READFUNC_PAUSE.toULong
      case None => 0.toULong
    }
}

private[curl] object RequestSend {
  def apply(flowControl: FlowControl): Resource[IO, RequestSend] = for {
    requestBodyChunk <- Ref[SyncIO].of(Option(ByteVector.empty)).to[IO].toResource
    requestBodyQueue <- Queue.synchronous[IO, Unit].toResource
    sendPause <- Ref[SyncIO].of(false).to[IO].toResource
    dispatcher <- Dispatcher.sequential[IO]
  } yield new RequestSend(
    flowControl,
    requestBodyChunk,
    requestBodyQueue,
    sendPause,
    dispatcher,
  )
  private[curl] def readCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte],
  ): CSize = Utils.fromPtr[RequestSend](userdata).onRead(buffer, size, nitems)

}
