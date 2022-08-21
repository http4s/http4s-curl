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
