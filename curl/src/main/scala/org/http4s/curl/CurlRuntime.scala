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

import scala.collection.mutable.ListBuffer
import scala.scalanative.unsafe._

import unsafe.libcurl

object CurlRuntime {
  def curlVersion: String = fromCString(libcurl.curl_version())

  private lazy val versionData = libcurl.curl_version_info(libcurl.CURLVERSION_NOW())

  /** curl version number encoded as hex 0xXXYYZZ
    * see here https://everything.curl.dev/libcurl/api
    */
  def curlVersionNumber: Int = libcurl.curl_version_number(versionData)

  /** curl version number (major, minor, patch) */
  def curlVersionTriple: (Int, Int, Int) = (
    (curlVersionNumber & 0xff0000) >> 16,
    (curlVersionNumber & 0x00ff00) >> 8,
    (curlVersionNumber & 0x0000ff),
  )

  def protocols: List[String] = {

    val all: ListBuffer[String] = ListBuffer.empty
    var cur: Ptr[CString] = libcurl.curl_protocols_info(versionData)
    while ((!cur).toLong != 0) {
      all.addOne(fromCString(!cur).toLowerCase)
      cur = cur + 1
    }
    all.toList
  }

  def isWebsocketAvailable: Boolean = protocols.contains("ws")

}
