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

package org.http4s.curl.unsafe

import cats.effect.FiberIO
import cats.effect.FileDescriptorPollHandle
import cats.effect.FileDescriptorPoller
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.AtomicCell
import cats.effect.std.Dispatcher
import cats.syntax.all._
import org.http4s.curl.CurlError
import org.http4s.curl.internal._

import scala.concurrent.duration._
import scala.scalanative.unsafe._

private[curl] object CurlMultiSocket {
  implicit private class OptFibOps(private val f: Option[FiberIO[?]]) extends AnyVal {
    def cancel: IO[Unit] = f.fold(IO.unit)(_.cancel)
  }

  private val getFDPoller = IO.pollers.flatMap(
    _.collectFirst { case poller: FileDescriptorPoller => poller }.liftTo[IO](
      new RuntimeException("Installed PollingSystem does not provide a FileDescriptorPoller")
    )
  )

  private val newCurlMutli = Resource.make(IO {
    val multiHandle = libcurl.curl_multi_init()
    if (multiHandle == null)
      throw new RuntimeException("curl_multi_init")
    multiHandle
  })(mhandle =>
    IO {
      val code = libcurl.curl_multi_cleanup(mhandle)
      if (code.isError)
        throw CurlError.fromMCode(code)
    }
  )

  private lazy val curlGlobalSetup = {
    val initCode = libcurl.curl_global_init(2)
    if (initCode.isError)
      throw CurlError.fromCode(initCode)
  }

  def apply(): Resource[IO, CurlMulti] = for {
    _ <- IO(curlGlobalSetup).toResource
    handle <- newCurlMutli
    fdPoller <- getFDPoller.toResource
    disp <- Dispatcher.sequential[IO]
    mapping <- AtomicCell[IO].of(State.empty).toResource
    timeout <- IO.ref[Option[FiberIO[Unit]]](None).toResource
    cms = new CurlMultiSocketImpl(handle, fdPoller, mapping, disp, timeout)
    _ <- cms.setup
  } yield cms

  final private case class Monitoring(
      read: Option[FiberIO[Nothing]],
      write: Option[FiberIO[Nothing]],
      handle: FileDescriptorPollHandle,
      unregister: IO[Unit],
  ) {
    def clean: IO[Unit] = IO.uncancelable(_ => read.cancel !> write.cancel !> unregister)
  }

  sealed private trait State
  private object State {
    val empty: State = Active()
    final case class Active(monitors: Map[libcurl.curl_socket_t, Monitoring] = Map.empty)
        extends State {
      def add(fd: libcurl.curl_socket_t, monitor: Monitoring): Active = copy(
        monitors.updated(fd, monitor)
      )
      def remove(fd: libcurl.curl_socket_t): Active = copy(monitors - fd)
    }
    case object Released extends State
  }

  private def onTimeout(
      mutli: Ptr[libcurl.CURLM],
      timeoutMs: CLong,
      userdata: Ptr[Byte],
  ): CInt = {
    val d = Utils.fromPtr[CurlMultiSocketImpl](userdata)

    if (timeoutMs == -1) {
      d.removeTimeout
    } else {
      d.setTimeout(timeoutMs)
    }
    0
  }

  private def onSocket(
      easy: Ptr[libcurl.CURL],
      fd: libcurl.curl_socket_t,
      what: Int,
      userdata: Ptr[Byte],
      socketdata: Ptr[Byte],
  ): CInt = {
    val d = Utils.fromPtr[CurlMultiSocketImpl](userdata)

    what match {
      case libcurl_const.CURL_POLL_IN => d.addFD(fd, true, false)
      case libcurl_const.CURL_POLL_OUT => d.addFD(fd, false, true)
      case libcurl_const.CURL_POLL_INOUT => d.addFD(fd, true, true)
      case libcurl_const.CURL_POLL_REMOVE => d.remove(fd)
      case other => throw new UnknownError(s"Received unknown socket request: $other!")
    }

    0
  }

  final private class CurlMultiSocketImpl(
      multiHandle: Ptr[libcurl.CURLM],
      fdpoller: FileDescriptorPoller,
      mapping: AtomicCell[IO, State],
      disp: Dispatcher[IO],
      timeout: Ref[IO, Option[FiberIO[Unit]]],
  ) extends CurlMulti {

    private def init = IO {
      val data = Utils.toPtr(this)

      libcurl
        .curl_multi_setopt_timerdata(
          multiHandle,
          libcurl_const.CURLMOPT_TIMERDATA,
          data,
        )
        .throwOnError

      libcurl
        .curl_multi_setopt_socketdata(
          multiHandle,
          libcurl_const.CURLMOPT_SOCKETDATA,
          data,
        )
        .throwOnError

      libcurl
        .curl_multi_setopt_timerfunction(
          multiHandle,
          libcurl_const.CURLMOPT_TIMERFUNCTION,
          onTimeout(_, _, _),
        )
        .throwOnError

      libcurl
        .curl_multi_setopt_socketfunction(
          multiHandle,
          libcurl_const.CURLMOPT_SOCKETFUNCTION,
          onSocket(_, _, _, _, _),
        )
        .throwOnError

    } *> notifyTimeout

    private def cleanup =
      removeTimeoutIO !> mapping.evalUpdate {
        case State.Active(monitors) =>
          // First clean all monitors, this ensures that we don't call any
          // curl callbacks afterwards, and callback cleaning and notifications
          // is deterministic.
          monitors.values.toList.traverse(_.clean) !> IO {
            val error = new InterruptedException("Runtime shutdown!")

            // Remove and notify all easy handles
            // Note that we do this in mapping.evalUpdate in order to block
            // other new usages while cleaning up
            callbacks.foreach { case (easy, cb) =>
              libcurl.curl_multi_remove_handle(multiHandle, easy).throwOnError
              cb(Left(error))
            }
            callbacks.clear()
          }.as(State.Released)
        case State.Released =>
          // It must not happen, but we leave a clue here if it happened!
          IO.raiseError(new IllegalStateException("Cannot clean a released resource!"))
      }

    def setup: Resource[IO, Unit] = Resource.make(init)(_ => cleanup)

    private val callbacks =
      scala.collection.mutable.Map[Ptr[libcurl.CURL], Either[Throwable, Unit] => Unit]()

    override def addHandlerTerminating(
        easy: CurlEasy,
        cb: Either[Throwable, Unit] => Unit,
    ): IO[Unit] = IO {
      libcurl.curl_multi_add_handle(multiHandle, easy.curl).throwOnError
      callbacks(easy.curl) = cb
    }

    override def addHandlerNonTerminating(
        easy: CurlEasy,
        cb: Either[Throwable, Unit] => Unit,
    ): Resource[IO, Unit] =
      Resource.make(addHandlerTerminating(easy, cb))(_ =>
        IO {
          libcurl.curl_multi_remove_handle(multiHandle, easy.curl).throwOnError
          callbacks.remove(easy.curl).foreach(_(Right(())))
        }
      )

    def addFD(fd: libcurl.curl_socket_t, read: Boolean, write: Boolean): Unit =
      disp.unsafeRunAndForget {

        val newMonitor = fdpoller.registerFileDescriptor(fd, read, write).allocated.flatMap {
          case (handle, unregister) =>
            (
              Option.when(read)(readLoop(fd, handle)).sequence,
              Option.when(write)(writeLoop(fd, handle)).sequence,
            )
              .mapN(Monitoring(_, _, handle, unregister))
        }

        IO.uncancelable(_ =>
          mapping.evalUpdate {
            case state @ State.Active(monitors) =>
              monitors.get(fd) match {
                case None =>
                  newMonitor.map(state.add(fd, _))
                case Some(s: Monitoring) =>
                  s.clean *> newMonitor.map(state.add(fd, _))
              }
            case State.Released =>
              IO.raiseError(new IllegalStateException("Runtime is already closed!"))
          }
        )
      }

    def remove(fd: libcurl.curl_socket_t): Unit =
      disp.unsafeRunAndForget(
        IO.uncancelable(_ =>
          mapping.evalUpdate {
            case state @ State.Active(monitors) =>
              monitors.get(fd) match {
                case None => IO(state)
                case Some(s) => s.clean.as(state.remove(fd))
              }
            case State.Released =>
              IO.raiseError(new IllegalStateException("Runtime is already closed!"))
          }
        )
      )

    def setTimeout(duration: Long): Unit = disp.unsafeRunAndForget(
      (IO.sleep(duration.millis) *> notifyTimeout).start.flatMap(f =>
        timeout.getAndSet(Some(f)).flatMap(_.cancel)
      )
    )

    private def removeTimeoutIO = timeout.getAndSet(None).flatMap(_.cancel)
    def removeTimeout: Unit = disp.unsafeRunAndForget(removeTimeoutIO)

    def notifyTimeout: IO[Unit] = IO {
      val running = stackalloc[Int]()
      libcurl
        .curl_multi_socket_action(multiHandle, libcurl_const.CURL_SOCKET_TIMEOUT, 0, running)
        .throwOnError

      postAction
    }

    private def postAction = while ({
      val msgsInQueue = stackalloc[CInt]()
      val info = libcurl.curl_multi_info_read(multiHandle, msgsInQueue)

      if (info != null) {
        val curMsg = libcurl.curl_CURLMsg_msg(info)
        if (curMsg == libcurl_const.CURLMSG_DONE) {
          val handle = libcurl.curl_CURLMsg_easy_handle(info)
          callbacks.remove(handle).foreach { cb =>
            val result = libcurl.curl_CURLMsg_data_result(info)
            cb(
              if (result.isOk) Right(())
              else Left(CurlError.fromCode(result))
            )
          }

          val code = libcurl.curl_multi_remove_handle(multiHandle, handle)
          if (code.isError)
            throw CurlError.fromMCode(code)
        }
        true
      } else false
    }) {}

    private def action(fd: libcurl.curl_socket_t, ev: CInt) = IO {
      val running = stackalloc[Int]()
      libcurl.curl_multi_socket_action(multiHandle, fd, ev, running)

      postAction

      Left(())
    }
    private def readLoop(fd: libcurl.curl_socket_t, p: FileDescriptorPollHandle) =
      p.pollReadRec(())(_ => action(fd, libcurl_const.CURL_CSELECT_IN)).start
    private def writeLoop(fd: libcurl.curl_socket_t, p: FileDescriptorPollHandle) =
      p.pollWriteRec(())(_ => action(fd, libcurl_const.CURL_CSELECT_OUT)).start
  }
}
