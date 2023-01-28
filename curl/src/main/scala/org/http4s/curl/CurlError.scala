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

import scala.scalanative.unsafe.fromCString

import unsafe.libcurl._

sealed abstract class CurlError(msg: String) extends RuntimeException(msg)

object CurlError {
  final case class CurlEasyError(code: CURLcode, info: String, details: Option[String] = None)
      extends CurlError(
        s"curl responded with error code: ${code.value}\ninfo: $info${details.map(d => s"\ndetails: $d").getOrElse("")}"
      )
  final case class CurlMultiError(code: CURLMcode, info: String)
      extends CurlError(
        s"curl multi interface responded with error code: ${code.value}\n$info"
      )

  private[curl] def fromCode(code: CURLcode) = {
    val info = fromCString(curl_easy_strerror(code))
    new CurlEasyError(code, info)
  }
  private[curl] def fromCode(code: CURLcode, details: String) = {
    val info = fromCString(curl_easy_strerror(code))
    new CurlEasyError(code, info, Some(details))
  }

  private[curl] def fromMCode(code: CURLMcode) = {
    val info = fromCString(curl_multi_strerror(code))
    new CurlMultiError(code, info)
  }
}
