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
          ec.addHandle(handle)
          handle
        }
      } { handle =>
        IO {
          ec.removeHandle(handle)
          libcurl.curl_easy_cleanup(handle)
        }
      }

      _ <- Resource.eval {
        IO {
          Zone { implicit z =>
            libcurl.curl_easy_setopt(handle, libcurl.CURLOPT_URL, toCString(req.uri.renderString))
          }
        }
      }
    } yield ???
  }

}
