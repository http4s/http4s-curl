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

/** Helper to keep references of objects in memory explicitly
  * When you want to make sure that GC won't clean them up until
  * this GCRoot is alive
  */
final private[curl] class GCRoot private (private val root: Ref[IO, Set[Any]]) extends AnyVal {
  @inline def add(objs: Any*): Resource[IO, Unit] = Resource.eval(root.update(_ ++ objs))
}

private[curl] object GCRoot {
  def apply(): Resource[IO, GCRoot] = Resource
    .make(IO.ref(Set.empty[Any]))(_.set(Set.empty))
    .map(new GCRoot(_))
}
