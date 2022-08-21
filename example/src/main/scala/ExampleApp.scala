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

package example

import cats.effect._
import io.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.curl.CurlApp
import cats.syntax.all._

object ExampleApp extends CurlApp.Simple {

  case class Joke(joke: String)
  object Joke {
    implicit val decoder: Decoder[Joke] = Decoder.forProduct1("joke")(Joke(_))
  }

  def run: IO[Unit] = for {
    responses <- curlClient.expect[Joke]("https://icanhazdadjoke.com/").parReplicateA(3)
    _ <- responses.traverse_(r => IO.println(r.joke))
  } yield ()

}
