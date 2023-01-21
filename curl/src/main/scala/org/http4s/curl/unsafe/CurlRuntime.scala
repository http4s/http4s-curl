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
package unsafe

import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntimeConfig
import cats.effect.unsafe.Scheduler

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.scalanative.unsafe._

object CurlRuntime {

  def apply(): IORuntime = apply(IORuntimeConfig())

  def apply(config: IORuntimeConfig): IORuntime = {
    val (ecScheduler, shutdown) = defaultExecutionContextScheduler()
    IORuntime(ecScheduler, ecScheduler, ecScheduler, shutdown, config)
  }

  def defaultExecutionContextScheduler(): (ExecutionContext with Scheduler, () => Unit) = {
    val (ecScheduler, shutdown) = CurlExecutorScheduler(64)
    (ecScheduler, shutdown)
  }

  private[this] var _global: IORuntime = null

  private[curl] def installGlobal(global: => IORuntime): Boolean =
    if (_global == null) {
      _global = global
      true
    } else {
      false
    }

  lazy val global: IORuntime = {
    if (_global == null) {
      installGlobal {
        CurlRuntime()
      }
    }

    _global
  }

  def curlVersion: String = fromCString(libcurl.curl_version())

  def protocols: List[String] = {

    val all: ListBuffer[String] = ListBuffer.empty
    var cur: Ptr[CString] = libcurl.curl_protocols_info()
    while ((!cur).toLong != 0) {
      all.addOne(fromCString(!cur).toLowerCase)
      cur = cur + 1
    }
    all.toList
  }

  def isWebsocketAvailable: Boolean = protocols.contains("ws")

}
