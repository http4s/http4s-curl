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

package org.http4s.curl.internal

import cats.effect.IO
import cats.effect.kernel.Resource

private[curl] trait CurlMultiDriver {

  /** Adds a curl handler that is expected to terminate
    * like a normal http request
    *
    * IMPORTANT NOTE: if you add a transfer that does not terminate (e.g. websocket) using this method,
    * application might hang, because those transfer don't seem to change state,
    * so it's not distinguishable whether they are finished or have other work to do
    *
    * @param handle curl easy handle to add
    * @param cb callback to run when this handler has finished its transfer
    */
  def addHandlerTerminating(easy: CurlEasy, cb: Either[Throwable, Unit] => Unit): IO[Unit]

  /** Add a curl handle for a transfer that doesn't finish e.g. a websocket transfer
    * it adds a handle to multi handle, and removes it when it goes out of scope
    * so no dangling handler will remain in multi handler
    * callback is called when the transfer is terminated or goes out of scope
    *
    * @param handle curl easy handle to add
    * @param cb callback to run if this handler is terminated unexpectedly
    */
  def addHandlerNonTerminating(
      easy: CurlEasy,
      cb: Either[Throwable, Unit] => Unit,
  ): Resource[IO, Unit]
}
