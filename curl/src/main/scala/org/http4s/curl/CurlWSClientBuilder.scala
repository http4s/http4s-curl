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
import org.http4s.client.websocket.WSClient
import org.http4s.curl.internal.CurlMultiDriver
import org.http4s.curl.websocket.CurlWSClient

final class CurlWSClientBuilder private[curl] (
    driver: CurlMultiDriver,
    val isVerbose: Boolean = false,
    val recvBufferSize: Int = 100,
    val pause: Int = 10,
    val resume: Int = 30,
) {
  private def copy(
      isVerbose: Boolean = isVerbose,
      recvBufferSize: Int = recvBufferSize,
      pause: Int = pause,
      resume: Int = resume,
  ) = new CurlWSClientBuilder(
    driver,
    isVerbose = isVerbose,
    recvBufferSize = recvBufferSize,
    pause = pause,
    resume = resume,
  )

  def setVerbose: CurlWSClientBuilder = copy(isVerbose = true)
  def notVerbose: CurlWSClientBuilder = copy(isVerbose = false)

  def withRecvBufferSize(value: Int): CurlWSClientBuilder = {
    assert(value > 0, "buffer size must be greater than zero!")
    copy(recvBufferSize = value)
  }
  def withBackpressure(pause: Int, resume: Int): CurlWSClientBuilder = {
    assert(pause >= 0 && pause < 100, "pause must be in [0, 100)")
    assert(resume > 0 && resume <= 100, "resume must be in (0, 100]")
    copy(pause = pause, resume = resume)
  }

  def build: Either[RuntimeException, WSClient[IO]] = CurlWSClient(
    driver,
    recvBufferSize = recvBufferSize,
    pauseOn = pause * recvBufferSize / 100,
    resumeOn = resume * recvBufferSize / 100,
    verbose = isVerbose,
  ).toRight(
    new RuntimeException(
      """Websocket is not supported in this environment!
You need to have curl with version 7.87.0 or higher with websockets enabled.
Note that websocket support in curl is experimental and is not available by default,
so you need to either build it with websocket support or use an already built libcurl with websocket support."""
    )
  )
  def buildIO: IO[WSClient[IO]] = IO.fromEither(build)
}
