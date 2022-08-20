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

import cats.effect.IOApp
import cats.effect.unsafe.IORuntime
import org.http4s.curl.unsafe.CurlRuntime

trait CurlApp extends IOApp {

  final override lazy val runtime: IORuntime = CurlRuntime(runtimeConfig)

}

object CurlApp {
  trait Simple extends IOApp.Simple with CurlApp
}
