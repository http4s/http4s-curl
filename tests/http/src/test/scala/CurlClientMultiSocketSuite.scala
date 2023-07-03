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
import cats.effect.SyncIO
import cats.effect.std.Random
import cats.syntax.all._
import munit.CatsEffectSuite
import org.http4s.Method._
import org.http4s.Request
import org.http4s.Status
import org.http4s.client.Client
import org.http4s.curl.unsafe.CurlMultiSocket
import org.http4s.syntax.all._

class CurlClientMultiSocketSuite extends CatsEffectSuite {

  val clientFixture: SyncIO[FunFixture[Client[IO]]] = ResourceFunFixture(
    CurlMultiSocket().map(http.CurlClient.multiSocket(_))
  )

  clientFixture.test("3 get echos") { client =>
    client
      .expect[String]("http://localhost:8080/http")
      .map(_.nonEmpty)
      .assert
      .parReplicateA_(3)
  }

  clientFixture.test("500") { client =>
    client
      .statusFromString("http://localhost:8080/http/500")
      .assertEquals(Status.InternalServerError)
  }

  clientFixture.test("unexpected") { client =>
    client
      .expect[String]("http://localhost:8080/http/500")
      .attempt
      .map(_.isLeft)
      .assert
  }

  clientFixture.test("error") { client =>
    client.expect[String]("unsupported://server").intercept[CurlError]
  }

  clientFixture.test("error") { client =>
    client.expect[String]("").intercept[CurlError]
  }

  clientFixture.test("3 post echos") { client =>
    Random.scalaUtilRandom[IO].flatMap { random =>
      random
        .nextString(8)
        .flatMap { s =>
          val msg = s"hello postman $s"
          client
            .expect[String](
              Request[IO](POST, uri = uri"http://localhost:8080/http/echo").withEntity(msg)
            )
            .flatTap(IO.println)
            .map(_.contains(msg))
            .assert
        }
        .parReplicateA_(3)
    }
  }

}
