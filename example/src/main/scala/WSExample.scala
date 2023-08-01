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

import cats.effect._
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest
import org.http4s.curl.CurlApp
import org.http4s.curl.CurlDriver
import org.http4s.implicits._

import scala.concurrent.duration._

object WSExample extends CurlApp.Simple {

  val fastData: Uri = uri"wss://stream.binance.com/ws/btcusdt@aggTrade"
  val largeData: Uri = uri"wss://stream.binance.com/ws/!ticker@arr"
  private val local = uri"ws://localhost:8080/ws/large"
  private val websocket = local

  def run: IO[Unit] = CurlDriver.default
    .evalMap(_.websocket.setVerbose.buildIO)
    .flatMap(_.connectHighLevel(WSRequest(websocket)))
    .use { client =>
      IO.println("ready!") >>
        client.receiveStream.foreach(_ => IO.println("> frame").delayBy(50.millis)).compile.drain
    }

}

object WSEchoExample extends CurlApp.Simple {

  // private val echo = uri"wss://ws.postman-echo.com/raw"
  private val echo = uri"ws://localhost:8080/ws/echo"

  def run: IO[Unit] = CurlDriver.default
    .evalMap(_.websocket.buildIO)
    .flatMap(_.connectHighLevel(WSRequest(echo)))
    .use { client =>
      val send: IO[Unit] =
        IO.println("sending ...") >> client.send(WSFrame.Text("hello")).parReplicateA_(4)

      IO.println("ready!") >>
        client.receiveStream
          .take(4)
          .printlns
          .compile
          .drain
          .both(send)
          .void
    }

}
