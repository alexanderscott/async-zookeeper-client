package com.github.partycoder.zookeeper

import org.apache.zookeeper.KeeperException.Code
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.data.Stat
import java.lang.RuntimeException

sealed trait AsyncResponse {
  def ctx: Option[Any]
  def code: Code
}

object AsyncResponse {
  trait SuccessAsyncResponse extends AsyncResponse {
    val code = Code.OK
  }

  case class FailedAsyncResponse(exception: KeeperException, path: Option[String], stat: Option[Stat], ctx: Option[Any])
    extends RuntimeException(exception) with AsyncResponse {
    val code = exception.code
  }

  case class ChildrenResponse(children: Seq[String], path: String, stat: Stat, ctx: Option[Any]) extends SuccessAsyncResponse
  case class StatResponse(path: String, stat: Stat, ctx: Option[Any]) extends SuccessAsyncResponse
  case class VoidResponse(path: String, ctx: Option[Any]) extends SuccessAsyncResponse
  case class DataResponse(data: Option[Array[Byte]], path: String, stat: Stat, ctx: Option[Any]) extends SuccessAsyncResponse
  case class DeleteResponse(children: Seq[String], path: String, stat: Stat, ctx: Option[Any]) extends SuccessAsyncResponse
  case class StringResponse(name: String, path: String, ctx: Option[Any]) extends SuccessAsyncResponse
}
