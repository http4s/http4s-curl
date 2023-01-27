import java.io.IOException
import java.net.URL
import sbt._
import Keys._
import scala.util.{Failure, Success, Try}
import scala.sys.process.Process

object TestServer {

  def setClassPath(cp: Seq[File]): Unit =
    this.cp = cp

  def setLog(log: Logger): Unit =
    this.log = Option(log)

  def start(): Unit = {
    if (proc.exists(_.isAlive())) return ()

    log.foreach(_.info("starting application ..."))
    val options = ForkOptions()

    // build classpath string
    val cpSep = if (scala.util.Properties.isWin) ";" else ":"
    val cpStr = cp.map(_.getAbsolutePath).mkString(cpSep)
    val arguments: Seq[String] = List("-classpath", cpStr)
    val mainClass: String = "org.http4s.test.Main"

    proc = Option(Fork.java.fork(options, arguments :+ mainClass))

    waitForStart().recover { case e =>
      stop()
      throw e
    }.get
  }

  def stop(): Unit = {
    log.foreach(_.info(s"stopping application $proc ..."))
    proc.foreach(_.destroy())
    proc = None
  }

  private def waitForStart(): Try[_] = {
    val maxAttempts = 10
    val u = new URL("http://localhost:8080")
    val c = u.openConnection()
    val result = (1 to maxAttempts).toStream
      .map { i =>
        log.foreach(_.info(s"connection attempt $i of $maxAttempts"))
        Try(c.connect())
      }
      .find {
        case Success(_) => true
        case Failure(e: IOException) => Thread.sleep(1000); false
        case Failure(_) => false
      }
    if (result.isEmpty)
      Failure(new RuntimeException(s"Failed to connect to application after $maxAttempts attempts"))
    else
      Success(None)
  }

  private var log: Option[Logger] = None
  private var cp: Seq[File] = Nil
  private var proc: Option[Process] = None
}
