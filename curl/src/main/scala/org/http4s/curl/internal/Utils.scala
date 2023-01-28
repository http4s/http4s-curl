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

import scala.scalanative.runtime
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.unsafe._

private[curl] object Utils {
  val newZone: Resource[IO, Zone] = Resource.make(IO(Zone.open()))(z => IO(z.close()))

  def toPtr(a: AnyRef): Ptr[Byte] =
    runtime.fromRawPtr(Intrinsics.castObjectToRawPtr(a))

  def fromPtr[A](ptr: Ptr[Byte]): A =
    Intrinsics.castRawPtrToObject(runtime.toRawPtr(ptr)).asInstanceOf[A]
}
