# http4s-curl

A client for [http4s](https://http4s.org/) on [Scala Native](https://github.com/scala-native/scala-native/), backed by [libcurl](https://curl.se/libcurl/). Check out the [example](https://github.com/http4s/http4s-curl/blob/main/example/src/main/scala/ExampleApp.scala).

- Non-blocking, with support for running multiple concurrent requests in parallel
- Streaming request and response bodies with backpressure
- Full access to [http4s client middleware, DSL, and auth APIs](https://www.javadoc.io/doc/org.http4s/http4s-docs_2.13/0.23.16/org/http4s/client/index.html)

```scala
libraryDependencies ++= Seq(
  "org.http4s" %%% "http4s-curl" % "0.1.1"
)
```

### Acknowledgements

Special thanks to:
- [@lolgab](https://github.com/lolgab/) for publishing the [first Scala Native async client](https://github.com/lolgab/scala-native-http-client-async).
- [@keynmol](https://github.com/keynmol/) whose [sn-bindgen](https://github.com/indoorvivants/sn-bindgen) enabled gratuitous copy-pasta.
- [@djspiewak](https://github.com/djspiewak/) for pushing the [`PollingExecutorScheduler`](https://github.com/typelevel/cats-effect/blob/05e6a4c34f284670b776b2890a12819b0a5c5954/core/native/src/main/scala/cats/effect/unsafe/PollingExecutorScheduler.scala) design in Cats Effect Native.
