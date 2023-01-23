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
import cats.effect.SyncIO
import cats.effect.implicits._
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.std.QueueSource
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.websocket.WSFrame._
import org.http4s.client.websocket._
import org.http4s.curl.unsafe.CurlExecutorScheduler
import org.http4s.curl.unsafe.CurlRuntime
import org.http4s.curl.unsafe.libcurl
import org.http4s.curl.unsafe.libcurl_const
import scodec.bits.ByteVector

import scala.annotation.unused
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import internal.Utils.throwOnError

import org.http4s.curl.internal.Utils
private[curl] object WebSocketClient {

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
        internal.Utils.toPtr(con),
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
      internal.Utils.toPtr(con),
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

    ec.addHandle(con.handler, _ => ())
  }

  implicit private class FrameMetaOps(private val meta: Ptr[libcurl.curl_ws_frame]) extends AnyVal {
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

  /** libcurl write callback */
  private def recvCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
      userdata: Ptr[Byte],
  ): CSize =
    internal.Utils
      .fromPtr[Connection](userdata)
      .onReceive(buffer, size, nmemb)

  private def headerCallback(
      @unused buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte],
  ): CSize = {
    internal.Utils
      .fromPtr[Connection](userdata)
      .onEstablished()

    size * nitems
  }

  final private class Connection(
      val handler: Ptr[libcurl.CURL],
      receivedQ: Queue[IO, WSFrame],
      receiving: Ref[SyncIO, Option[Receiving]],
      established: Deferred[IO, Unit],
      dispatcher: Dispatcher[IO],
  ) {

    /** received frames */
    val received: QueueSource[IO, WSFrame] = receivedQ

    private def enqueue(wsframe: WSFrame): Unit =
      dispatcher.unsafeRunAndForget(receivedQ.offer(wsframe))

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

    def send(flags: CInt, data: ByteVector): IO[Unit] =
      established.get >>
        internal.Utils.newZone.use { implicit zone =>
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

  private def createConnection(recvBufferSize: Int) =
    (
      internal.Utils.createHandler,
      Resource.eval(Queue.bounded[IO, WSFrame](recvBufferSize)),
      Ref[SyncIO].of(Option.empty[Receiving]).to[IO].toResource,
      IO.deferred[Unit].toResource,
      Dispatcher.sequential[IO],
    ).parMapN(new Connection(_, _, _, _, _))

  def apply(
      ec: CurlExecutorScheduler,
      recvBufferSize: Int = 10,
  ): Option[WSClient[IO]] =
    Option.when(CurlRuntime.isWebsocketAvailable) {
      WSClient(true) { req =>
        internal.Utils.newZone
          .flatMap(implicit zone =>
            createConnection(recvBufferSize)
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

              override def receive: IO[Option[WSFrame]] = con.received.take.map(_.some)

              override def subprotocol: Option[String] = None

            }
          )
      }
    }

  sealed trait Error extends Throwable with Serializable with Product
  case object InvalidTextFrame extends Exception("Text frame data must be valid utf8") with Error
  case object PartialFragmentFrame
      extends Exception("Partial fragments are not supported by this driver")
      with Error
  case object InvalidFrame extends Exception("Text frame data must be valid utf8") with Error
  case object InvalidRuntime
      extends RuntimeException("Not running on CurlExecutorScheduler")
      with Error

}
