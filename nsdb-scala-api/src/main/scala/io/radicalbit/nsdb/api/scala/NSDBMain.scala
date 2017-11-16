package io.radicalbit.nsdb.api.scala

import io.radicalbit.nsdb.rpc.response.RPCInsertResult

import scala.concurrent._
import scala.concurrent.duration._

object NSDBMain extends App {

  val nsdb = NSDB.connect(host = "127.0.0.1", port = 7817, db = "root")(ExecutionContext.global)

  val series = nsdb
    .namespace("registry")
    .bit("people")
    .value(10)
    .dimension("city", "Mouseton")
    .dimension("gender", "M")

  val res: Future[RPCInsertResult] = nsdb.write(series)

  println(Await.result(res, 10 seconds))
}
