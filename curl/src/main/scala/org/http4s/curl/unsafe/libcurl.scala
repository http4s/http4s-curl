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

  type CURLMSG = CUnsignedInt
  final val CURLMSG_DONE = 1L
  type CURLMsg = CStruct3[CURLMSG, Ptr[CURL], CURLcode]

  type CURLoption = CUnsignedInt
  final val CURLOPTTYPE_LONG = 0
  final val CURLOPTTYPE_OBJECTPOINT = 10000
  final val CURLOPTTYPE_FUNCTIONPOINT = 20000
  final val CURLOPTTYPE_STRINGPOINT = CURLOPTTYPE_OBJECTPOINT
  final val CURLOPTTYPE_SLISTPOINT = CURLOPTTYPE_OBJECTPOINT
  final val CURLOPT_CUSTOMREQUEST = CURLOPTTYPE_OBJECTPOINT + 36
  final val CURLOPT_URL = CURLOPTTYPE_STRINGPOINT + 2
  final val CURLOPT_HTTPHEADER = CURLOPTTYPE_STRINGPOINT + 23
  final val CURLOPT_HTTP_VERSION = CURLOPTTYPE_LONG + 84
  final val CURLOPT_HEADERFUNCTION = CURLOPTTYPE_FUNCTIONPOINT + 79
  final val CURLOPT_WRITEFUNCTION = CURLOPTTYPE_FUNCTIONPOINT + 11
  final val CURLOPT_READFUNCTION = CURLOPTTYPE_FUNCTIONPOINT + 12

  final val CURL_HTTP_VERSION_NONE = 0L
  final val CURL_HTTP_VERSION_1_0 = 1L
  final val CURL_HTTP_VERSION_1_1 = 2L
  final val CURL_HTTP_VERSION_2 = 3L
  final val CURL_HTTP_VERSION_3 = 30L

  final val CURLPAUSE_RECV = 1 << 0
  final val CURLPAUSE_RECV_CONT = 0

  final val CURLPAUSE_SEND = 1 << 2
  final val CURLPAUSE_SEND_CONT = 0

  final val CURLPAUSE_ALL = CURLPAUSE_RECV | CURLPAUSE_SEND
  final val CURLPAUSE_CONT = CURLPAUSE_RECV_CONT | CURLPAUSE_SEND_CONT

  final val CURL_WRITEFUNC_PAUSE = 0x10000001L
  final val CURL_READFUNC_ABORT = 0x10000000L
  final val CURL_READFUNC_PAUSE = 0x10000001L

  type curl_slist

  type header_callback = CFuncPtr4[Ptr[CChar], CSize, CSize, Ptr[Byte], CSize]

  type write_callback = CFuncPtr4[Ptr[CChar], CSize, CSize, Ptr[Byte], CSize]

  type read_callback = CFuncPtr4[Ptr[CChar], CSize, CSize, Ptr[Byte], CSize]

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

  def curl_multi_info_read(multi_handle: Ptr[CURLM], msgs_in_queue: Ptr[CInt]): Ptr[CURLMsg] =
    extern

  def curl_multi_add_handle(multi_handle: Ptr[CURLM], curl_handle: Ptr[CURL]): CURLMcode = extern

  def curl_multi_remove_handle(multi_handle: Ptr[CURLM], curl_handle: Ptr[CURL]): CURLMcode = extern

  def curl_easy_init(): Ptr[CURL] = extern

  def curl_easy_cleanup(curl: Ptr[CURL]): Unit = extern

  def curl_easy_pause(handle: Ptr[CURL], bitmask: CInt): CURLcode = extern

  @name("curl_easy_setopt")
  def curl_easy_setopt_url(curl: Ptr[CURL], option: CURLOPT_URL.type, URL: Ptr[CChar]): CURLcode =
    extern

  @name("curl_easy_setopt")
  def curl_easy_setopt_customrequest(
      curl: Ptr[CURL],
      option: CURLOPT_CUSTOMREQUEST.type,
      request: Ptr[CChar],
  ): CURLcode =
    extern

  @name("curl_easy_setopt")
  def curl_easy_setopt_httpheader(
      curl: Ptr[CURL],
      option: CURLOPT_HTTPHEADER.type,
      headers: Ptr[curl_slist],
  ): CURLcode =
    extern

  @name("curl_easy_setopt")
  def curl_easy_setopt_http_version(
      curl: Ptr[CURL],
      option: CURLOPT_HTTP_VERSION.type,
      version: CLong,
  ): CURLcode =
    extern

  @name("curl_easy_setopt")
  def curl_easy_setopt_headerfunction(
      curl: Ptr[CURL],
      option: CURLOPT_HEADERFUNCTION.type,
      header_callback: header_callback,
  ): CURLcode =
    extern

  @name("curl_easy_setopt")
  def curl_easy_setopt_writefunction(
      curl: Ptr[CURL],
      option: CURLOPT_WRITEFUNCTION.type,
      write_callback: write_callback,
  ): CURLcode =
    extern

  @name("curl_easy_setopt")
  def curl_easy_setopt_readfunction(
      curl: Ptr[CURL],
      option: CURLOPT_READFUNCTION.type,
      read_callback: read_callback,
  ): CURLcode =
    extern

  def curl_slist_append(list: Ptr[curl_slist], string: Ptr[CChar]): Ptr[curl_slist] = extern

  def curl_slist_free_all(list: Ptr[curl_slist]): Unit = extern

}
