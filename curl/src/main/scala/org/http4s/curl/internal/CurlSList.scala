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

import cats.effect.IO
import cats.effect.Resource
import org.http4s.curl.unsafe.libcurl._

import scala.scalanative.unsafe._

final private[curl] class CurlSList private (private var list: Ptr[curl_slist])(implicit
    zone: Zone
) {
  @inline def append(str: String): Unit = list = curl_slist_append(list, toCString(str))
  @inline val toPtr = list
}

private[curl] object CurlSList {
  def apply()(implicit zone: Zone): Resource[IO, CurlSList] =
    Resource.make(IO(new CurlSList(list = null)))(slist => IO(curl_slist_free_all(slist.list)))
}
