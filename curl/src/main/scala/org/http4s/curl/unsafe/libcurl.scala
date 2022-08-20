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

import scala.annotation.nowarn
import scala.scalanative.unsafe._

@link("curl")
@extern
@nowarn
private[curl] object libcurl {

  type CURL
  type CURLcode = CInt
  type CURLM
  type CURLMcode = CInt

  type CURLoption = CUnsignedInt
  final val CURLOPTTYPE_OBJECTPOINT = 10000
  final val CURLOPT_URL = CURLOPTTYPE_OBJECTPOINT + 2

  def curl_global_init(flags: CLongInt): CURLcode = extern

  def curl_global_cleanup(): Unit = extern

  def curl_multi_init(): Ptr[CURLM] = extern

  def curl_multi_cleanup(multi_handle: Ptr[CURLM]): CURLMcode = extern

  def curl_multi_poll(
      multi_handle: Ptr[CURLM],
      extra_fds: Ptr[Byte],
      extra_nfds: CUnsignedInt,
      timeout_ms: CInt,
      numfds: Ptr[CInt],
  ): CURLMcode = extern

  def curl_multi_perform(multi_handle: Ptr[CURLM], running_handles: Ptr[CInt]): CURLMcode = extern

  def curl_multi_add_handle(multi_handle: Ptr[CURLM], curl_handle: Ptr[CURL]): CURLMcode = extern

  def curl_multi_remove_handle(multi_handle: Ptr[CURLM], curl_handle: Ptr[CURL]): CURLMcode = extern

  def curl_easy_init(): Ptr[CURL] = extern

  def curl_easy_cleanup(curl: Ptr[CURL]): Unit = extern

  def curl_easy_setopt(curl: Ptr[CURL], option: CURLOPT_URL.type, URL: Ptr[CChar]): CURLcode =
    extern

}
