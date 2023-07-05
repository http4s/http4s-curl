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
import org.http4s.client.Client
import org.http4s.curl.CurlDriver
import org.http4s.curl.internal.CurlMultiDriver

private[curl] object CurlClient {
  def apply(ms: CurlMultiDriver, isVerbose: Boolean = false): Client[IO] = Client(
    CurlRequest(ms, _, isVerbose)
  )

  val default: Resource[IO, Client[IO]] = CurlDriver.default.map(_.http.build)
}
