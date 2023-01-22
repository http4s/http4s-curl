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
import org.http4s.client.websocket.WSRequest
import org.http4s.curl.CurlApp
import org.http4s.implicits._

// import scala.concurrent.duration._

object WSExample extends CurlApp.Simple {

  private val fastData = uri"wss://stream.binance.com/ws/btcusdt@aggTrade"
  // private val largeData = uri"wss://stream.binance.com/ws/!ticker@arr"
  // private val echo = uri"wss://ws.postman-echo.com/raw"

  def run: IO[Unit] = websocketOrError()
    .connectHighLevel(WSRequest(fastData))
    .use { client =>
      // def send(retry: Int = 3): IO[Unit] =
      //   IO.println("sending ...") >> client.send(WSFrame.Text("hello")).recoverWith {
      //     case _ if retry > 0 => send(retry - 1).delayBy(2.seconds)
      //   }

      IO.println("ready!") >>
        client.receiveStream.printlns.compile.drain // .both(send().delayBy(2.seconds)).void
    }

}
