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

package org.http4s.curl.websocket

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.implicits._
import org.http4s.curl.internal.Utils.throwOnError
import org.http4s.curl.unsafe.libcurl
import org.http4s.curl.unsafe.libcurl_const

import scala.scalanative.unsafe._

/** Internal circuit breaker for websocket receive
  * starts at max capacity,
  * drain reduces cap,
  * feed increases cap,
  * reaching open threshold pauses receive,
  * reaching close threshold unpauses receive
  */
final private class Breaker private (
    handler: Ptr[libcurl.CURL],
    capacity: Ref[IO, Int],
    close: Int,
    open: Int,
    verbose: Boolean,
) {
  private val unpauseRecv = IO.blocking {
    if (verbose) println("continue recv")

    throwOnError(
      libcurl.curl_easy_pause(
        handler,
        libcurl_const.CURLPAUSE_RECV_CONT,
      )
    )
  }

  private val pauseRecv = IO.blocking {
    if (verbose) println("pause recv")
    throwOnError(
      libcurl.curl_easy_pause(
        handler,
        libcurl_const.CURLPAUSE_RECV,
      )
    )
  }

  def drain: IO[Unit] = capacity
    .updateAndGet(_ - 1)
    .flatMap(cap =>
      if (cap == open) pauseRecv
      else if (verbose) IO.println(s"* BREAKER: decreased to $cap")
      else IO.unit
    )

  def feed: IO[Unit] = capacity
    .updateAndGet(_ + 1)
    .flatMap(cap =>
      if (cap == close) unpauseRecv
      else if (verbose) IO.println(s"* BREAKER: increased to $cap")
      else IO.unit
    )
}

private object Breaker {
  def apply(
      handler: Ptr[libcurl.CURL],
      capacity: Int,
      close: Int,
      open: Int,
      verbose: Boolean = false,
  ): IO[Breaker] = {
    assert(open <= close, s"open must be less than or equal to close but $open > $close")
    assert(close < capacity, s"close must be less than capacity but $close >= $capacity")
    IO
      .ref(capacity)
      .map(
        new Breaker(
          handler,
          _,
          close = close,
          open = open,
          verbose,
        )
      )
  }
}
