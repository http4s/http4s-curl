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

package org.http4s.test

import cats.effect.IO
import cats.effect.std.Random
import fs2.Stream
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import scodec.bits.ByteVector

import scala.concurrent.duration._

object TestServer {
  def apply(wsb: WebSocketBuilder2[IO]): HttpApp[IO] = Router(
    "http" -> TestHttpServer(),
    "ws" -> TestWSServer(wsb),
  ).orNotFound
}

object TestHttpServer {
  def apply(): HttpRoutes[IO] = HttpRoutes.of {
    case GET -> Root => IO.randomUUID.map(_.toString).flatMap(Ok(_))
    case GET -> Root / "404" => NotFound()
    case GET -> Root / "500" => InternalServerError()
    case req @ POST -> Root / "echo" => req.as[String].flatMap(Ok(_))
  }
}

object TestWSServer {
  def apply(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of {
    case GET -> Root / "echo" => wsb.build(identity)
    case GET -> Root / "fast" =>
      wsb.build(Stream.awakeEvery[IO](50.millis).map(f => WebSocketFrame.Text(f.toString)), _.drain)
    case GET -> Root / "large" => wsb.build(largeData, _.drain)
    case GET -> Root / "bounded" =>
      wsb.build(Stream.emit(WebSocketFrame.Text("everything")), _.drain)
    case GET -> Root / "closed" =>
      wsb.build(Stream.emit(WebSocketFrame.Close()), _.drain)
  }
  private val largeData = for {
    rng <- Stream.eval(Random.scalaUtilRandom[IO])
    _ <- Stream.awakeEvery[IO](500.millis)
    payload <- Stream.eval(rng.nextBytes(1 << 20))
  } yield WebSocketFrame.Binary(ByteVector(payload))
}
