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
import cats.effect.kernel.Resource
import org.http4s.curl.internal.CurlMultiDriver
import org.http4s.curl.unsafe.CurlMultiPerformPoller
import org.http4s.curl.unsafe.CurlMultiSocket

final class CurlDriver private (driver: CurlMultiDriver) {
  def http: CurlClientBuilder = new CurlClientBuilder(driver)
  def websocket: CurlWSClientBuilder = new CurlWSClientBuilder(driver)
}

object CurlDriver {
  val default: Resource[IO, CurlDriver] = IO.pollers.toResource.flatMap {
    _.collectFirst { case mp: CurlMultiPerformPoller => Resource.eval(IO(new CurlDriver(mp))) }
      .getOrElse(CurlMultiSocket().map(new CurlDriver(_)))
  }
}
