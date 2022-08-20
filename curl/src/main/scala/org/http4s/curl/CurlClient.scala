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
import org.http4s.HttpVersion
import org.http4s.client.Client
import org.http4s.curl.unsafe.CurlExecutorScheduler
import org.http4s.curl.unsafe.libcurl

import scala.scalanative.unsafe._

private[curl] object CurlClient {

  def apply(ec: CurlExecutorScheduler): Client[IO] = Client { req =>
    for {
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

          }
        }
      }

      _ <- Resource.make(IO(ec.addHandle(handle)))(_ => IO(ec.removeHandle(handle)))
    } yield ???
  }

}
