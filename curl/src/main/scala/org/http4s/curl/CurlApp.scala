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
import cats.effect.IOApp
import cats.effect.unsafe.IORuntime
import org.http4s.client.Client
import org.http4s.client.websocket.WSClient
import org.http4s.curl.unsafe.CurlExecutorScheduler
import org.http4s.curl.unsafe.CurlRuntime

trait CurlApp extends IOApp {

  final override lazy val runtime: IORuntime = {
    val installed = CurlRuntime.installGlobal {
      CurlRuntime(runtimeConfig)
    }

    if (!installed) {
      System.err
        .println(
          "WARNING: CurlRuntime global runtime already initialized; custom configurations will be ignored"
        )
    }

    CurlRuntime.global
  }

  private def scheduler = runtime.compute.asInstanceOf[CurlExecutorScheduler]
  final lazy val curlClient: Client[IO] = CurlClient(scheduler)

  /** gets websocket client if current libcurl environment supports it */
  final def websocket(recvBufferSize: Int = 100): Option[WSClient[IO]] =
    WebSocketClient(scheduler, recvBufferSize)

  /** gets websocket client if current libcurl environment supports it throws an error otherwise */
  final def websocketOrError(recvBufferSize: Int = 100): WSClient[IO] =
    websocket(recvBufferSize).getOrElse(
      throw new RuntimeException(
        """Websocket is not supported in this environment!
Note that websocket support in curl is experimental and is not available by default,
so you need to either build it with websocket support or use an already built libcurl with websocket support."""
      )
    )

}

object CurlApp {
  trait Simple extends IOApp.Simple with CurlApp
}
