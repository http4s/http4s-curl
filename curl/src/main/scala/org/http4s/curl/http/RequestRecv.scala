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
import cats.effect.std._
import cats.syntax.all._
import fs2.Chunk
import fs2.Stream
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpVersion
import org.http4s.Response
import org.http4s.Status
import org.http4s.curl.internal.Utils
import org.http4s.curl.unsafe.libcurl_const
import org.typelevel.ci._
import scodec.bits.ByteVector

import scalanative.unsigned._
import scalanative.unsafe._

final private[curl] class RequestRecv private (
    flowControl: FlowControl,
    responseD: Deferred[IO, Either[Throwable, Response[IO]]],
    responseBodyQueueReady: Ref[SyncIO, Boolean],
    responseBodyQueue: Queue[IO, Option[ByteVector]],
    responseBuilder: Ref[IO, Option[Response[IO]]],
    trailerHeaders: Deferred[IO, Either[Throwable, Headers]],
    trailerHeadersBuilder: Ref[IO, Headers],
    done: Deferred[IO, Either[Throwable, Unit]],
    dispatcher: Dispatcher[IO],
) {
  @inline val responseBody: Stream[IO, Byte] = Stream
    .repeatEval(
      // sequencing is important! the docs for `curl_easy_pause` say:
      // > When this function is called to unpause receiving,
      // > the chance is high that you will get your write callback called before this function returns.
      // so it's important to indicate that the queue is ready before unpausing recv
      responseBodyQueueReady.set(true).to[IO] *> flowControl.unpauseRecv *> responseBodyQueue.take
    )
    .unNoneTerminate
    .map(Chunk.byteVector(_))
    .unchunks

  @inline def response(): Resource[IO, Response[IO]] = responseD.get.rethrow
    .map(_.withBodyStream(responseBody).withTrailerHeaders(trailerHeaders.get.rethrow))
    .toResource

  @inline def onTerminated(x: Either[Throwable, Unit]): Unit =
    dispatcher.unsafeRunAndForget(
      // TODO refactor to make it simpler
      x.fold(x => responseD.complete(Left(x)), _ => IO.unit) *>
        done.complete(x) *> responseBodyQueue.offer(None)
    )

  @inline def onWrite(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
  ): CSize =
    if (responseBodyQueueReady.get.unsafeRunSync()) {
      responseBodyQueueReady.set(false)
      dispatcher.unsafeRunAndForget(
        responseBodyQueue.offer(Some(ByteVector.fromPtr(buffer, nmemb.toLong)))
      )
      size * nmemb
    } else {
      flowControl.onRecvPaused.unsafeRunSync()
      libcurl_const.CURL_WRITEFUNC_PAUSE.toULong
    }
  @inline def onHeader(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
  ): CSize = {
    val decoded = ByteVector
      .view(buffer, nitems.toLong)
      .decodeAscii
      .liftTo[IO]

    def parseHeader(header: String): IO[Header.Raw] =
      header.dropRight(2).split(": ") match {
        case Array(name, value) => IO.pure(Header.Raw(CIString(name), value))
        case _ => IO.raiseError(new RuntimeException("header_callback"))
      }

    val go = responseD.tryGet
      .map(_.isEmpty)
      .ifM(
        // prelude
        responseBuilder.get
          .flatMap {
            case None =>
              decoded.map(_.split(' ')).flatMap {
                case Array(v, c, _*) =>
                  for {
                    version <- HttpVersion.fromString(v).liftTo[IO]
                    status <- IO(c.toInt).flatMap(Status.fromInt(_).liftTo[IO])
                    _ <- responseBuilder.set(Some(Response[IO](status, version)))
                  } yield ()
                case _ => IO.raiseError(new RuntimeException("header_callback"))
              }
            case Some(wipResponse) =>
              decoded.flatMap {
                case "\r\n" => responseD.complete(Right(wipResponse))
                case header =>
                  parseHeader(header)
                    .flatMap(h => responseBuilder.set(Some(wipResponse.putHeaders(h))))
              }
          }
          .onError(ex => responseD.complete(Left(ex)).void),

        // trailers
        done.tryGet
          .flatMap {
            case Some(result) =>
              trailerHeadersBuilder.get
                .flatMap(h => trailerHeaders.complete(result.as(h)))
            case None =>
              decoded.flatMap { header =>
                parseHeader(header)
                  .flatMap(h => trailerHeadersBuilder.update(_.put(h)))
              }
          }
          .onError(ex => trailerHeaders.complete(Left(ex)).void),
      )

    dispatcher.unsafeRunAndForget(go)

    size * nitems
  }
}

private[curl] object RequestRecv {
  def apply(flowControl: FlowControl): Resource[IO, RequestRecv] = for {
    done <- IO.deferred[Either[Throwable, Unit]].toResource
    responseBodyQueueReady <- Ref[SyncIO].of(false).to[IO].toResource
    responseBodyQueue <- Queue.synchronous[IO, Option[ByteVector]].toResource

    trailerHeadersBuilder <- IO.ref(Headers.empty).toResource
    trailerHeaders <- IO.deferred[Either[Throwable, Headers]].toResource

    responseBuilder <- IO
      .ref(Option.empty[Response[IO]])
      .toResource
    response <- IO.deferred[Either[Throwable, Response[IO]]].toResource
    dispatcher <- Dispatcher.sequential[IO]
  } yield new RequestRecv(
    flowControl,
    response,
    responseBodyQueueReady,
    responseBodyQueue,
    responseBuilder = responseBuilder,
    trailerHeaders = trailerHeaders,
    trailerHeadersBuilder = trailerHeadersBuilder,
    done,
    dispatcher,
  )

  private[curl] def headerCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte],
  ): CSize =
    Utils.fromPtr[RequestRecv](userdata).onHeader(buffer, size, nitems)

  private[curl] def writeCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
      userdata: Ptr[Byte],
  ): CSize =
    Utils.fromPtr[RequestRecv](userdata).onWrite(buffer, size, nmemb)

}
