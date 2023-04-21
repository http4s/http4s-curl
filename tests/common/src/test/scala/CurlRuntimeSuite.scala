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

import munit.FunSuite
import org.http4s.curl.unsafe.CurlRuntime

class CurlRuntimeSuite extends FunSuite {

  test("curl version") {
    val prefixPattern = """^libcurl\/[78]\..+$""".r
    assert(prefixPattern.matches(CurlRuntime.curlVersion))
  }

  test("curl version number") {
    assert(CurlRuntime.curlVersionNumber > 0x070000)
    assert(CurlRuntime.curlVersionTriple._1 >= 7)
  }

  test("curl protocols") {
    val protocols = CurlRuntime.protocols
    assert(protocols.contains("http"))
    assert(protocols.contains("https"))
  }

}
