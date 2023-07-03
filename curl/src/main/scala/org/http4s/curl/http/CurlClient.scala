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
import org.http4s.curl.unsafe.CurlExecutorScheduler
import org.http4s.curl.unsafe.CurlMultiSocket

private[curl] object CurlClient {
  def apply(ec: CurlExecutorScheduler): Client[IO] = Client(CurlRequest(ec, _))

  def multiSocket(ms: CurlMultiSocket): Client[IO] = Client(CurlRequest.applyMultiSocket(ms, _))

  def get: IO[Client[IO]] = IO.executionContext.flatMap {
    case ec: CurlExecutorScheduler => IO.pure(apply(ec))
    case _ => IO.raiseError(new RuntimeException("Not running on CurlExecutorScheduler"))
  }
}
