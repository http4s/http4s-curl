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

import org.http4s.curl.CurlError

import scala.scalanative.unsafe._

final private[curl] case class CURLcode(value: CInt) extends AnyVal {
  @inline def isOk: Boolean = value == 0
  @inline def isError: Boolean = value != 0
  @inline def throwOnError: Unit =
    if (isError) {
      throw CurlError.fromCode(this)
    }
}
