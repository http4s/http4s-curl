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
import cats.effect.kernel.Ref
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.Stream
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpVersion
import org.http4s.Response
import org.http4s.Status
import org.http4s.client.Client
import org.http4s.curl.unsafe.CurlExecutorScheduler
import org.http4s.curl.unsafe.libcurl
import org.typelevel.ci.CIString
import scodec.bits.ByteVector

import java.util.Collections
import java.util.IdentityHashMap
import scala.scalanative.unsafe._
import fs2.Chunk

private[curl] object CurlClient {

  def apply(ec: CurlExecutorScheduler): Client[IO] = Client { req =>
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

      unpauseRecv = recvPause.set(false).to[IO] *>
        sendPause.get.to[IO].flatMap { p =>
          IO {
            libcurl.curl_easy_pause(
              handle,
              if (p) libcurl.CURLPAUSE_SEND else libcurl.CURLPAUSE_SEND_CONT,
            )
          }
        }

      responseBodyQueue <- Queue.synchronous[IO, Option[ByteVector]].toResource
      responseBody = Stream
        .repeatEval(unpauseRecv *> responseBodyQueue.take)
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
          Zone { implicit z =>
            @inline def throwOnError(thunk: => libcurl.CURLcode): Unit = {
              val code = thunk
              if (code != 0)
                throw new RuntimeException(s"curl_easy_setop: $code")
            }

            throwOnError(
              libcurl.curl_easy_setopt_customrequest(
                handle,
                libcurl.CURLOPT_CUSTOMREQUEST,
                toCString(req.method.renderString),
              )
            )

            throwOnError(
              libcurl.curl_easy_setopt_url(
                handle,
                libcurl.CURLOPT_URL,
                toCString(req.uri.renderString),
              )
            )

            val httpVersion = req.httpVersion match {
              case HttpVersion.`HTTP/1.0` => libcurl.CURL_HTTP_VERSION_1_0
              case HttpVersion.`HTTP/1.1` => libcurl.CURL_HTTP_VERSION_1_1
              case HttpVersion.`HTTP/2` => libcurl.CURL_HTTP_VERSION_2
              case HttpVersion.`HTTP/3` => libcurl.CURL_HTTP_VERSION_3
              case _ => libcurl.CURL_HTTP_VERSION_NONE
            }
            throwOnError(
              libcurl.curl_easy_setopt_http_version(
                handle,
                libcurl.CURLOPT_HTTP_VERSION,
                httpVersion,
              )
            )

            var headers: Ptr[libcurl.curl_slist] = null
            req.headers.foreach { header =>
              headers = libcurl.curl_slist_append(headers, toCString(header.toString))
            }
            throwOnError(
              libcurl.curl_easy_setopt_httpheader(handle, libcurl.CURLOPT_HTTPHEADER, headers)
            )
            libcurl.curl_slist_free_all(headers)

            throwOnError {
              val headerCallback: libcurl.header_callback = {
                (buffer: Ptr[CChar], _: CSize, nitems: CSize, _: Ptr[Byte]) =>
                  val decoded = ByteVector
                    .view(buffer, nitems.toLong)
                    .decodeAscii
                    .liftTo[IO]

                  def parseHeader(header: String): IO[Header.Raw] =
                    header.split(": ") match {
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
                              case Array(v, c, _) =>
                                for {
                                  version <- HttpVersion.fromString(v).liftTo[IO]
                                  status <- IO(c.toInt).flatMap(Status.fromInt(_).liftTo[IO])
                                  _ <- responseBuilder.set(
                                    Some(
                                      Response(status, version, body = responseBody)
                                        .withTrailerHeaders(trailerHeaders.get.rethrow)
                                    )
                                  )
                                } yield ()
                              case _ => IO.raiseError(new RuntimeException("header_callback"))
                            }
                          case Some(wipResponse) =>
                            decoded.flatMap {
                              case "" => response.complete(Right(wipResponse))
                              case header =>
                                parseHeader(header).flatMap(h =>
                                  responseBuilder.set(Some(wipResponse.withHeaders(h)))
                                )
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

                  nitems
              }

              gcRoot.add(headerCallback)
              libcurl.curl_easy_setopt_headerfunction(
                handle,
                libcurl.CURLOPT_HEADERFUNCTION,
                headerCallback,
              )
            }

            throwOnError {
              val writeCallback: libcurl.write_callback = {
                (buffer: Ptr[CChar], _: CSize, nmemb: CSize, _: Ptr[Byte]) =>
                  if (recvPause.getAndSet(true).unsafeRunSync())
                    libcurl.CURL_WRITEFUNC_PAUSE
                  else {
                    dispatcher.unsafeRunAndForget(
                      responseBodyQueue.offer(Some(ByteVector.fromPtr(buffer, nmemb.toLong)))
                    )
                    nmemb
                  }
              }

              gcRoot.add(writeCallback)
              libcurl.curl_easy_setopt_writefunction(
                handle,
                libcurl.CURLOPT_WRITEFUNCTION,
                writeCallback,
              )
            }

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

      readyResponse <- response.get.rethrow.toResource
    } yield readyResponse
  }

}
