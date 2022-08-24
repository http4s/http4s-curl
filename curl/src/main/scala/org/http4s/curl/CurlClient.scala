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

import cats.effect.IO
import cats.effect.Resource
import cats.effect.SyncIO
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.Chunk
import fs2.Stream
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpVersion
import org.http4s.Response
import org.http4s.Status
import org.http4s.client.Client
import org.http4s.curl.unsafe.CurlExecutorScheduler
import org.http4s.curl.unsafe.libcurl
import org.http4s.curl.unsafe.libcurl_const
import org.typelevel.ci._
import scodec.bits.ByteVector

import java.util.Collections
import java.util.IdentityHashMap
import scala.scalanative.runtime
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[curl] object CurlClient {

  def get: IO[Client[IO]] = IO.executionContext.flatMap {
    case ec: CurlExecutorScheduler => IO.pure(apply(ec))
    case _ => IO.raiseError(new RuntimeException("Not running on CurlExecutorScheduler"))
  }

  def apply(ec: CurlExecutorScheduler): Client[IO] = Client { req =>
    Resource.make(IO(Zone.open()))(z => IO(z.close())).flatMap { implicit z =>
      for {
        gcRoot <- Resource.make {
          IO(Collections.newSetFromMap[Any](new IdentityHashMap))
        } { gcr =>
          IO(gcr.clear())
        }

        dispatcher <- Dispatcher.sequential[IO]

        handle <- Resource.make {
          IO {
            val handle = libcurl.curl_easy_init()
            if (handle == null)
              throw new RuntimeException("curl_easy_init")
            handle
          }
        } { handle =>
          IO(libcurl.curl_easy_cleanup(handle))
        }

        done <- IO.deferred[Either[Throwable, Unit]].toResource

        recvPause <- Ref[SyncIO].of(false).to[IO].toResource
        sendPause <- Ref[SyncIO].of(false).to[IO].toResource

        unpauseRecv = recvPause
          .getAndSet(false)
          .to[IO]
          .ifM( // needs unpause
            sendPause.get.to[IO].flatMap { p =>
              IO {
                val code = libcurl.curl_easy_pause(
                  handle,
                  if (p) libcurl_const.CURLPAUSE_SEND else libcurl_const.CURLPAUSE_SEND_CONT,
                )
                if (code != 0)
                  throw new RuntimeException(s"curl_easy_pause: $code")
              }
            },
            IO.unit, // already unpaused
          )

        unpauseSend = sendPause.set(false).to[IO] *>
          recvPause.get.to[IO].flatMap { p =>
            IO {
              val code = libcurl.curl_easy_pause(
                handle,
                if (p) libcurl_const.CURLPAUSE_RECV else libcurl_const.CURLPAUSE_RECV_CONT,
              )
              if (code != 0)
                throw new RuntimeException(s"curl_easy_pause: $code")
            }
          }

        requestBodyChunk <- Ref[SyncIO].of(Option(ByteVector.empty)).to[IO].toResource
        requestBodyQueue <- Queue.synchronous[IO, Unit].toResource
        _ <- req.body.chunks
          .map(_.toByteVector)
          .noneTerminate
          .foreach { chunk =>
            requestBodyQueue.take *> requestBodyChunk.set(chunk).to[IO] *> unpauseSend
          }
          .compile
          .drain
          .background

        responseBodyQueueReady <- Ref[SyncIO].of(false).to[IO].toResource
        responseBodyQueue <- Queue.synchronous[IO, Option[ByteVector]].toResource
        responseBody = Stream
          .repeatEval(
            unpauseRecv *> responseBodyQueue.take <* responseBodyQueueReady.set(true).to[IO]
          )
          .unNoneTerminate
          .map(Chunk.byteVector(_))
          .unchunks

        trailerHeadersBuilder <- IO.ref(Headers.empty).toResource
        trailerHeaders <- IO.deferred[Either[Throwable, Headers]].toResource

        responseBuilder <- IO
          .ref(Option.empty[Response[IO]])
          .toResource
        response <- IO.deferred[Either[Throwable, Response[IO]]].toResource

        _ <- Resource.eval {
          IO {
            @inline def throwOnError(thunk: => libcurl.CURLcode): Unit = {
              val code = thunk
              if (code != 0)
                throw new RuntimeException(s"curl_easy_setop: $code")
            }

            throwOnError(
              libcurl.curl_easy_setopt_customrequest(
                handle,
                libcurl_const.CURLOPT_CUSTOMREQUEST,
                toCString(req.method.renderString),
              )
            )

            throwOnError(
              libcurl.curl_easy_setopt_upload(
                handle,
                libcurl_const.CURLOPT_UPLOAD,
                1,
              )
            )

            throwOnError(
              libcurl.curl_easy_setopt_url(
                handle,
                libcurl_const.CURLOPT_URL,
                toCString(req.uri.renderString),
              )
            )

            val httpVersion = req.httpVersion match {
              case HttpVersion.`HTTP/1.0` => libcurl_const.CURL_HTTP_VERSION_1_0
              case HttpVersion.`HTTP/1.1` => libcurl_const.CURL_HTTP_VERSION_1_1
              case HttpVersion.`HTTP/2` => libcurl_const.CURL_HTTP_VERSION_2
              case HttpVersion.`HTTP/3` => libcurl_const.CURL_HTTP_VERSION_3
              case _ => libcurl_const.CURL_HTTP_VERSION_NONE
            }
            throwOnError(
              libcurl.curl_easy_setopt_http_version(
                handle,
                libcurl_const.CURLOPT_HTTP_VERSION,
                httpVersion,
              )
            )

            var headers: Ptr[libcurl.curl_slist] = null
            req.headers // curl adds these headers automatically, so we explicitly disable them
              .transform(Header.Raw(ci"Expect", "") :: Header.Raw(ci"Transfer-Encoding", "") :: _)
              .foreach { header =>
                headers = libcurl.curl_slist_append(headers, toCString(header.toString))
              }
            throwOnError(
              libcurl.curl_easy_setopt_httpheader(handle, libcurl_const.CURLOPT_HTTPHEADER, headers)
            )

            throwOnError {
              val data = ReadCallbackData(
                requestBodyChunk,
                requestBodyQueue,
                sendPause,
                dispatcher,
              )

              gcRoot.add(data)

              libcurl.curl_easy_setopt_readdata(
                handle,
                libcurl_const.CURLOPT_READDATA,
                toPtr(data),
              )
            }

            throwOnError {
              libcurl.curl_easy_setopt_readfunction(
                handle,
                libcurl_const.CURLOPT_READFUNCTION,
                readCallback(_, _, _, _),
              )
            }

            throwOnError {
              val data = HeaderCallbackData(
                response,
                responseBuilder,
                trailerHeaders,
                trailerHeadersBuilder,
                done,
                dispatcher,
              )

              gcRoot.add(data)

              libcurl.curl_easy_setopt_headerdata(
                handle,
                libcurl_const.CURLOPT_HEADERDATA,
                toPtr(data),
              )
            }

            throwOnError {
              libcurl.curl_easy_setopt_headerfunction(
                handle,
                libcurl_const.CURLOPT_HEADERFUNCTION,
                headerCallback(_, _, _, _),
              )
            }

            throwOnError {
              val data = WriteCallbackData(
                recvPause,
                responseBodyQueueReady,
                responseBodyQueue,
                dispatcher,
              )

              gcRoot.add(data)

              libcurl.curl_easy_setopt_writedata(
                handle,
                libcurl_const.CURLOPT_WRITEDATA,
                toPtr(data),
              )
            }

            throwOnError {
              libcurl.curl_easy_setopt_writefunction(
                handle,
                libcurl_const.CURLOPT_WRITEFUNCTION,
                writeCallback(_, _, _, _),
              )
            }

          }
        }

        _ <- Resource.eval {
          IO {
            ec.addHandle(
              handle,
              x => dispatcher.unsafeRunAndForget(done.complete(x) *> responseBodyQueue.offer(None)),
            )
          }
        }

        readyResponse <- response.get.rethrow
          .map(_.withBodyStream(responseBody).withTrailerHeaders(trailerHeaders.get.rethrow))
          .toResource
      } yield readyResponse
    }
  }

  final private case class ReadCallbackData(
      requestBodyChunk: Ref[SyncIO, Option[ByteVector]],
      requestBodyQueue: Queue[IO, Unit],
      sendPause: Ref[SyncIO, Boolean],
      dispatcher: Dispatcher[IO],
  )

  private def readCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte],
  ): CSize = {
    val data = fromPtr[ReadCallbackData](userdata)
    import data._

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

  final private case class HeaderCallbackData(
      response: Deferred[IO, Either[Throwable, Response[IO]]],
      responseBuilder: Ref[IO, Option[Response[IO]]],
      trailerHeaders: Deferred[IO, Either[Throwable, Headers]],
      trailerHeadersBuilder: Ref[IO, Headers],
      done: Deferred[IO, Either[Throwable, Unit]],
      dispatcher: Dispatcher[IO],
  )

  private def headerCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nitems: CSize,
      userdata: Ptr[Byte],
  ): CSize = {
    val data = fromPtr[HeaderCallbackData](userdata)
    import data._

    val decoded = ByteVector
      .view(buffer, nitems.toLong)
      .decodeAscii
      .liftTo[IO]

    def parseHeader(header: String): IO[Header.Raw] =
      header.dropRight(2).split(": ") match {
        case Array(name, value) => IO.pure(Header.Raw(CIString(name), value))
        case _ => IO.raiseError(new RuntimeException("header_callback"))
      }

    val go = response.tryGet
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
                case "\r\n" => response.complete(Right(wipResponse))
                case header =>
                  parseHeader(header)
                    .flatMap(h => responseBuilder.set(Some(wipResponse.putHeaders(h))))
              }
          }
          .onError(ex => response.complete(Left(ex)).void),

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

  final private case class WriteCallbackData(
      recvPause: Ref[SyncIO, Boolean],
      responseBodyQueueReady: Ref[SyncIO, Boolean],
      responseBodyQueue: Queue[IO, Option[ByteVector]],
      dispatcher: Dispatcher[IO],
  )

  private def writeCallback(
      buffer: Ptr[CChar],
      size: CSize,
      nmemb: CSize,
      userdata: Ptr[Byte],
  ): CSize = {
    val data = fromPtr[WriteCallbackData](userdata)
    import data._

    if (responseBodyQueueReady.get.unsafeRunSync()) {
      responseBodyQueueReady.set(false)
      dispatcher.unsafeRunAndForget(
        responseBodyQueue.offer(Some(ByteVector.fromPtr(buffer, nmemb.toLong)))
      )
      size * nmemb
    } else {
      recvPause.set(true).unsafeRunSync()
      libcurl_const.CURL_WRITEFUNC_PAUSE.toULong
    }
  }

  private def toPtr(a: AnyRef): Ptr[Byte] =
    runtime.fromRawPtr(Intrinsics.castObjectToRawPtr(a))

  private def fromPtr[A](ptr: Ptr[Byte]): A =
    Intrinsics.castRawPtrToObject(runtime.toRawPtr(ptr)).asInstanceOf[A]

}
