package example

import cats.effect.IO
import org.http4s.curl.CurlApp

object ExampleApp extends CurlApp.Simple {

  def run: IO[Unit] = curlClient.expect[String]("https://example.com").flatMap(IO.println)

}
