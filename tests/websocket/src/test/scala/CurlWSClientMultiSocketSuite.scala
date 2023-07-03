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
import cats.syntax.all._
import munit.CatsEffectSuite
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest
import org.http4s.curl.unsafe.CurlMultiSocket
import org.http4s.curl.websocket.CurlWSClient
import org.http4s.implicits._

class CurlWSClientMultiSocketSuite extends CatsEffectSuite {

  private val clientFixture = ResourceFunFixture(
    CurlMultiSocket().evalMap(
      CurlWSClient(_).liftTo[IO](
        new RuntimeException("websocket client is not supported in this environment")
      )
    )
  )

  clientFixture.test("websocket echo") {
    val frames = List.range(1, 5).map(i => WSFrame.Text(s"text $i"))

    _.connectHighLevel(WSRequest(uri"ws://localhost:8080/ws/echo"))
      .use(con =>
        con.receiveStream
          .take(4)
          .evalTap(IO.println)
          .compile
          .toList <& (frames.traverse(con.send(_)))
      )
      .assertEquals(frames)
  }

  clientFixture.test("websocket bounded") {
    _.connectHighLevel(WSRequest(uri"ws://localhost:8080/ws/bounded"))
      .use(con =>
        con.receiveStream
          .evalTap(IO.println)
          .compile
          .toList
      )
      .assertEquals(List(WSFrame.Text("everything")))
  }

  clientFixture.test("websocket closed") {
    _.connectHighLevel(WSRequest(uri"ws://localhost:8080/ws/closed"))
      .use(con => con.receiveStream.compile.toList)
      .assertEquals(Nil)
      .parReplicateA_(4)
  }

  clientFixture.test("error") { client =>
    client
      .connectHighLevel(WSRequest(uri""))
      .use_
      .intercept[CurlError]
  }

  clientFixture.test("error") { client =>
    client
      .connectHighLevel(WSRequest(uri"server"))
      .use_
      .intercept[CurlError]
  }

  clientFixture.test("invalid protocol") { client =>
    client
      .connectHighLevel(WSRequest(uri"http://localhost:8080/http"))
      .use_
      .intercept[IllegalArgumentException]
  }
}
