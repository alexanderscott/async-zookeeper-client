package com.github.partycoder.zookeeper

import org.apache.zookeeper.KeeperException.Code
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.data.Stat
import java.lang.RuntimeException

sealed trait AsyncResponse {
  def code: Code
}

object AsyncResponse {
  trait SuccessAsyncResponse extends AsyncResponse {
    val code = Code.OK
  }

  case class FailedAsyncResponse(exception: KeeperException, path: Option[String], stat: Option[Stat])
    extends RuntimeException(exception) with AsyncResponse {
    val code = exception.code
  }

  case class ChildrenResponse(children: Seq[String], path: String, stat: Stat) extends SuccessAsyncResponse
  case class StatResponse(path: String, stat: Stat) extends SuccessAsyncResponse
  case class VoidResponse(path: String) extends SuccessAsyncResponse
  case class DataResponse(data: Option[Array[Byte]], path: String, stat: Stat) extends SuccessAsyncResponse
  case class StringResponse(name: String, path: String) extends SuccessAsyncResponse
}
