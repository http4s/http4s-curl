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

import cats.effect.IO
import cats.effect.Resource
import cats.effect.SyncIO
import cats.effect.implicits._
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.std.QueueSource
import cats.implicits._
import org.http4s.client.websocket._
import org.http4s.curl.internal.Utils
import org.http4s.curl.internal.Utils.throwOnError
import org.http4s.curl.unsafe.libcurl
import org.http4s.curl.unsafe.libcurl_const
import scodec.bits.ByteVector

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import Connection._

final private class Connection private (
    val handler: Ptr[libcurl.CURL],
    receivedQ: Queue[IO, Option[WSFrame]],
    receiving: Ref[SyncIO, Option[Receiving]],
    established: Deferred[IO, Unit],
    dispatcher: Dispatcher[IO],
) {

  /** received frames */
  val received: QueueSource[IO, Option[WSFrame]] = receivedQ

  private def enqueue(wsframe: WSFrame): Unit =
    dispatcher.unsafeRunAndForget(receivedQ.offer(wsframe.some))

  /** libcurl write callback */
  def onReceive(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
  ): CSize = {
    val realsize = size * nmemb
    val meta = libcurl.curl_easy_ws_meta(handler)
    // val toRead = if (meta.isFirstChunk) 0.toULong else realsize

    val toEnq = receiving
      .modify {
        case Some(value) =>
          val next = value.add(buffer, realsize)

          val optF = next.toFrame
          if (optF.isDefined) (None, optF)
          else (Some(next), None)
        case None =>
          if (meta.isClose) {
            (None, Some(WSFrame.Close(200, "Closed by server")))
          } else {
            val frameType =
              if (meta.isText) ReceivingType.Text
              else if (meta.isBinary) ReceivingType.Binary
              else if (meta.isPing) ReceivingType.Ping
              else throw InvalidFrame

            val recv =
              Receiving(buffer, realsize, meta.isFinal, frameType, meta.bytesLeft.toULong)

            val optF = recv.toFrame
            if (optF.isDefined) (None, optF)
            else (Some(recv), None)
          }
      }
      .unsafeRunSync()

    toEnq.foreach(enqueue)

    realsize
  }

  def onEstablished(): Unit = dispatcher.unsafeRunAndForget(established.complete(()))

  def onTerminated(result: Either[Throwable, Unit]): Unit = {
    dispatcher.unsafeRunAndForget(receivedQ.offer(None))
    result match {
      case Left(err) =>
        Console.err.println("Websocket connection terminated!")
        err.printStackTrace(Console.err)
      case _ => ()
    }
  }

  def send(flags: CInt, data: ByteVector): IO[Unit] =
    established.get >>
      Utils.newZone.use { implicit zone =>
        IO {
          val sent = stackalloc[CSize]()
          val buffer = data.toPtr
          val size = data.size.toULong

          throwOnError(
            libcurl
              .curl_easy_ws_send(handler, buffer, size, sent, 0.toULong, flags.toUInt)
          )
        }
      }
}

private object Connection {
  implicit class FrameMetaOps(private val meta: Ptr[libcurl.curl_ws_frame]) extends AnyVal {
    @inline def flags: CInt = !meta.at2
    @inline def isCont: Boolean = (flags & libcurl_const.CURLWS_CONT) != 0
    @inline def isFinal: Boolean = !isCont
    @inline def isText: Boolean = (flags & libcurl_const.CURLWS_TEXT) != 0
    @inline def isBinary: Boolean = (flags & libcurl_const.CURLWS_BINARY) != 0
    @inline def isPing: Boolean = (flags & libcurl_const.CURLWS_PING) != 0
    @inline def isClose: Boolean = (flags & libcurl_const.CURLWS_CLOSE) != 0
    @inline def offset: Long = !meta.at3
    @inline def bytesLeft: Long = !meta.at4
    @inline def noBytesLeft: Boolean = bytesLeft == 0

    /** this meta is for the first callback call of to be received data
      * Not to be confused with partial fragments!
      * A fragment can be chunked despite it being partial or not.
      */
    @inline def isFirstChunk: Boolean = offset == 0 && !noBytesLeft

    /** this meta is for a non chunked frame that has all the data available
      * in this callback
      */
    @inline def isNotChunked: Boolean = offset == 0 && noBytesLeft
  }

  def apply(recvBufferSize: Int): Resource[IO, Connection] =
    (
      Utils.createHandler,
      Resource.eval(Queue.bounded[IO, Option[WSFrame]](recvBufferSize)),
      Ref[SyncIO].of(Option.empty[Receiving]).to[IO].toResource,
      IO.deferred[Unit].toResource,
      Dispatcher.sequential[IO],
    ).parMapN(new Connection(_, _, _, _, _))
}

sealed private trait ReceivingType extends Serializable with Product
private object ReceivingType {
  case object Text extends ReceivingType
  case object Binary extends ReceivingType
  case object Ping extends ReceivingType
}

final private case class Receiving(
    payload: ByteVector,
    isFinal: Boolean,
    frameType: ReceivingType,
    left: CSize,
) {

  def add(buffer: Ptr[Byte], size: CSize): Receiving = {
    val remainedAfter = left - size
    assert(remainedAfter >= 0.toULong)
    copy(
      payload = payload ++ ByteVector.fromPtr(buffer, size.toLong),
      left = remainedAfter,
    )
  }
  def toFrame: Option[WSFrame] =
    Option.when(left.toLong == 0)(
      this match {
        case Receiving(payload, last, ReceivingType.Text, _) =>
          val str = payload.decodeUtf8.getOrElse(throw InvalidTextFrame)
          WSFrame.Text(str, last)
        case Receiving(payload, last, ReceivingType.Binary, _) =>
          WSFrame.Binary(payload, last)
        case Receiving(payload, _, ReceivingType.Ping, _) =>
          WSFrame.Ping(payload)
      }
    )
}
private object Receiving {
  def apply(
      buffer: Ptr[Byte],
      size: CSize,
      isFinal: Boolean,
      frameType: ReceivingType,
      left: CSize,
  ): Receiving = new Receiving(
    ByteVector.fromPtr(buffer, size.toLong),
    isFinal,
    frameType,
    left,
  )
}
