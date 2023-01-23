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

package org.http4s.curl.websocket

sealed trait Error extends Throwable with Serializable with Product
case object InvalidTextFrame extends Exception("Text frame data must be valid utf8") with Error
case object PartialFragmentFrame
    extends Exception("Partial fragments are not supported by this driver")
    with Error
case object InvalidFrame extends Exception("Text frame data must be valid utf8") with Error
case object InvalidRuntime
    extends RuntimeException("Not running on CurlExecutorScheduler")
    with Error
