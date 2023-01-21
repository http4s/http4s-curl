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

package org.http4s.curl.internal

import cats.effect._
import org.http4s.curl.unsafe.libcurl
import scala.scalanative.unsafe._
import scala.collection.mutable.ListBuffer

private[curl] object Utils {
  lazy val protocols: List[String] = {

    val all: ListBuffer[String] = ListBuffer.empty
    var cur: Ptr[CString] = libcurl.curl_protocols_info()
    while ((!cur).toLong != 0) {
      all.addOne(fromCString(!cur).toLowerCase)
      cur = cur + 1
    }
    all.toList
  }

  lazy val isWebsocketAvailable: Boolean = protocols.contains("ws")

  @inline def throwOnError(thunk: => libcurl.CURLcode): Unit = {
    val code = thunk
    if (code != 0)
      throw new RuntimeException(s"curl_easy_setop: $code")
  }

  val createHandler = Resource.make {
    IO {
      val handle = libcurl.curl_easy_init()
      if (handle == null)
        throw new RuntimeException("curl_easy_init")
      handle
    }
  } { handle =>
    IO(libcurl.curl_easy_cleanup(handle))
  }
  val newZone = Resource.make(IO(Zone.open()))(z => IO(z.close()))
}
