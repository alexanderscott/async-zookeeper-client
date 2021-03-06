package io.github.alexanderscott.zookeeper

import AsyncResponse.FailedAsyncResponse
import compat.Platform
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{TimeUnit, CountDownLatch, Executors}
import org.apache.zookeeper.KeeperException.{NoNodeException, NotEmptyException, BadVersionException}
import org.apache.zookeeper.Watcher.Event.EventType
import org.apache.zookeeper.{WatchedEvent, Watcher, CreateMode}
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, WordSpec}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import ExecutionContext.Implicits.global

class AsyncZooKeeperClientSpecs extends WordSpec with ShouldMatchers with BeforeAndAfter with BeforeAndAfterAll {
  val eService = Executors.newCachedThreadPool
  implicit val to = 3.seconds
  var zk: AsyncZooKeeperClient = _

  class DoAwait[T](f: Future[T]) {
    def await(implicit d: Duration): T = Await.result[T](f, d)
  }

  implicit def toDoAwait[T](f: Future[T]) = new DoAwait[T](f)
  val basePath = "/async-client/tests"

  before {
    val context = ExecutionContext.fromExecutorService(eService)
    zk = new AsyncZooKeeperClient("localhost:2181", 1000, 1000, basePath)(context)
  }

  after {
    (zk.deleteChildren("") map {
      case _ => zk.close()
    } recover {
      case _ => zk.close()
    }).await
  }

  override def afterAll() {
    eService.shutdown()
  }

  "A relative path should have base path prepended" in {
    AsyncZooKeeperClient.mkPath(basePath, "testers") should be(s"$basePath/testers")
  }

  "An empty path should be base path" in {
    AsyncZooKeeperClient.mkPath(basePath, "") should be(basePath)
  }

  "An absolute path should not have a base path" in {
    AsyncZooKeeperClient.mkPath(basePath, "/abs/path") should be("/abs/path")
  }

  "An absolute '/' path should be '/'" in {
    AsyncZooKeeperClient.mkPath(basePath, "/") should be("/")
  }

  "connecting should work with running server" in {
    Await.result(zk.isAlive, to) should be(true)
  }

  "creating a node" should {
    "work with string data" in {
      val s = zk.create("crabber", Some("crabber".getBytes), CreateMode.EPHEMERAL).await
      s.path should be("/async-client/tests/crabber")
      s.name should be("/async-client/tests/crabber")
    }
    "work with null data" in {
      val s = zk.create("crabber", None, CreateMode.EPHEMERAL).await
      s.path should be("/async-client/tests/crabber")
      s.name should be("/async-client/tests/crabber")
    }
  }

  "creating and getting a node" should {
    "work with string data" in {
      val s = zk.createAndGet("crabber", Some("crabber".getBytes), CreateMode.EPHEMERAL).await
      s.path should be("/async-client/tests/crabber")
      AsyncZooKeeperClient.deSerializeString(s.data.get) should be("crabber")
      s.stat.getNumChildren should be(0)
    }

    "work with null data" in {
      val s = zk.createAndGet("crabber", None, CreateMode.EPHEMERAL).await
      s.path should be("/async-client/tests/crabber")
      s.data should be('empty)
      s.stat.getMtime should be < Platform.currentTime
    }
  }

  "createPath should recursively create nodes" in {
    val stat = for {
      void <- zk.createPath("a/b/c/d/e/f/g")
      e1 <- zk.exists("a/b/c/d/e/f/g")
    } yield {
      e1
    }

    stat.await.path should be("/async-client/tests/a/b/c/d/e/f/g")
  }

  "Delete children should work" in {
    val stat = for {
      void <- zk.createPath("a/b/c/d/e/f/g")
      deleted <- zk.deleteChildren("")
      stat <- zk.exists("")
    } yield {
      stat
    }

    stat.await.stat.getNumChildren should be(0)
  }

  "delete" should {
    "fail if versions don't match" in {
      val void = for {
        str <- zk.create("blubbs", Some("blubbs".getBytes), CreateMode.EPHEMERAL)
        fail <- zk.delete("blubbs", 666)
      } yield fail

      intercept[FailedAsyncResponse] {
        void.await
      }.exception.isInstanceOf[BadVersionException] should be(true)
    }

    "fail if force is false and the node has children" in {
      val void = for {
        parent <- zk.createAndGet("blubbs", Some("blubbs".getBytes), CreateMode.PERSISTENT)
        kid <- zk.create("blubbs/clubbs", Some("clubbs".getBytes), CreateMode.EPHEMERAL)
        fail <- zk.delete("blubbs", parent.stat.getVersion)
      } yield fail

      intercept[FailedAsyncResponse] {
        void.await
      }.exception.isInstanceOf[NotEmptyException] should be(true)
    }

    "succeed if force is true and the node has children" in {
      val void = for {
        parent <- zk.createAndGet("blubbs", Some("blubbs".getBytes), CreateMode.PERSISTENT)
        kid <- zk.create("blubbs/clubbs", Some("clubbs".getBytes), CreateMode.EPHEMERAL)
        fail <- zk.delete("blubbs", parent.stat.getVersion, force = true)
      } yield fail

      void.await // no exception

    }

    "succeed if force is false and the node has no children" in {
      val void = for {
        parent <- zk.createAndGet("blubbs", Some("blubbs".getBytes), CreateMode.PERSISTENT)
        fail <- zk.delete("blubbs", parent.stat.getVersion)
      } yield fail

      void.await
    }
  }

  "set and get" should {
    "succeed if the node exists" in {
      val resp = for {
        init <- zk.create("crabs", null, CreateMode.PERSISTENT)
        set <- zk.set("crabs", Some("crabber".getBytes), 0)
        node <- zk.get("crabs")
      } yield node

      AsyncZooKeeperClient.deSerializeString(resp.await.data.get) should be ("crabber")
      resp.await.stat.getVersion should be(1)
    }

    "fail on set if the node doesn't exist" in {
      val resp = for {
        fail <- zk.set("crabs", Some("crabber".getBytes))
      } yield fail

      intercept[FailedAsyncResponse] {
        resp.await
      }.exception.isInstanceOf[NoNodeException] should be(true)
    }

    "succeed with null data" in {
      val resp = for {
        init <- zk.create("crabs", Some("crabber".getBytes), CreateMode.PERSISTENT)
        set <- zk.set("crabs", null, 0)
        node <- zk.get("crabs")
      } yield node

      resp.await.data should be('empty)
      resp.await.stat.getVersion should be(1)
    }
  }

  "watches when getting data" should {
    "be triggered when data changes" in {
      val waitForMe = new CountDownLatch(1)

      val watcher = new Watcher {
        def process(event: WatchedEvent) = if (event.getType == EventType.NodeDataChanged) waitForMe.countDown()
      }

      for {
        init <- zk.createAndGet("chubbs", Some("chubbs".getBytes), CreateMode.EPHEMERAL, watch = Some(watcher))
        seq <- zk.set("chubbs", Some("blubber".getBytes))
      } yield seq
      waitForMe.await(1, TimeUnit.SECONDS)
    }

    "be triggered after multiple reads" in {
      val waitForMe = new CountDownLatch(1)

      val watcher = new Watcher {
        def process(event: WatchedEvent) = {
          if (event.getType == EventType.NodeDataChanged) {
            waitForMe.countDown()
          }
        }
      }

      for {
        init <- zk.createAndGet("chubbs", Some("chubbs".getBytes), CreateMode.EPHEMERAL, watch = Some(watcher))
        data1 <- zk.get("chubbs")
        data2 <- zk.get("chubbs")
        seq <- zk.set("chubbs", Some("blubber".getBytes))
      } yield seq

      waitForMe.await(1, TimeUnit.SECONDS)
    }

    "be triggered for multiple watches after multiple reads" in {
      val waitForMe = new CountDownLatch(1)
      val waitForMeToo = new CountDownLatch(1)

      val watch1 = new Watcher {
        def process(event: WatchedEvent) = if (event.getType == EventType.NodeDataChanged) waitForMe.countDown()
      }

      val watch2 = new Watcher {
        def process(event: WatchedEvent) = if (event.getType == EventType.NodeDataChanged) waitForMeToo.countDown()
      }

      for {
        init <- zk.createAndGet("chubbs", Some("chubbs".getBytes), CreateMode.EPHEMERAL)
        data1 <- zk.get("chubbs", watch = Some(watch1))
        data2 <- zk.get("chubbs", watch = Some(watch2))
        seq <- zk.set("chubbs", Some("blubber".getBytes))
      } yield seq

      waitForMe.await(1, TimeUnit.SECONDS)
      waitForMeToo.await(1, TimeUnit.SECONDS)
    }
  }

  "creating ephemeral with the shortcut method nodes" in {
    zk.createEphemeral("a/b/c/d/e/f", Some("123".getBytes)).await should be(true)
  }

  "setting watches on exist" in {
    val waitForMe = new CountDownLatch(1)
    val waitForMeToo = new CountDownLatch(1)

    val watch1 = new Watcher {
      def process(event: WatchedEvent) = if (event.getType == EventType.NodeDataChanged) waitForMe.countDown()
    }

    val watch2 = new Watcher {
      def process(event: WatchedEvent) = if (event.getType == EventType.NodeDataChanged) waitForMeToo.countDown()
    }

    for {
      init <- zk.createAndGet("chubbs", Some("chubbs".getBytes), CreateMode.EPHEMERAL)
      data1 <- zk.exists("chubbs", watch = Some(watch1))
      data2 <- zk.get("chubbs", watch = Some(watch2))
      seq <- zk.set("chubbs", Some("blubber".getBytes))
    } yield seq

    waitForMe.await(1, TimeUnit.SECONDS)
    waitForMeToo.await(1, TimeUnit.SECONDS)
  }

  "setting watches on getChildren" in {
    val waitForMe = new CountDownLatch(1)
    val watch1 = new Watcher {
      def process(event: WatchedEvent) = if (event.getType == EventType.NodeChildrenChanged) waitForMe.countDown()
    }

    for {
      init <- zk.createAndGet("chubbs", Some("chubbs".getBytes), CreateMode.PERSISTENT)
      kids <- zk.getChildren("chubbs", watch = Some(watch1))
      void <- zk.createPath("chubbs/blubber")
    } yield void

    waitForMe.await(1, TimeUnit.SECONDS)
  }

  "watchData" should {
    "permanently watch any changes on node" in {
      val waitForMe = new CountDownLatch(3)
      val counter = new AtomicInteger(0)
      for {
        init <- zk.create("splats", Some(counter.get.toString.getBytes), CreateMode.PERSISTENT)
        watch <- zk.watchData("splats") {
          (path, data) => waitForMe.countDown()
        }
        one <- zk.set("splats", Some(counter.incrementAndGet.toString.getBytes))
        two <- zk.set("splats", Some(counter.incrementAndGet.toString.getBytes))
        three <- zk.set("splats", Some(counter.incrementAndGet.toString.getBytes))
      } yield three

      waitForMe.await(1, TimeUnit.SECONDS) should be(true)
      counter.get should be(3)
    }
  }

  "watchChildren" should {
    "permanently watch child changes" in {
      val waitForMe = new CountDownLatch(3)
      for {
        init <- zk.create("splats", None, CreateMode.PERSISTENT)
        watch <- zk.watchChildren("splats") {
          kids => waitForMe.countDown()
        }
        one <- zk.createPath("splats/one")
        two <- zk.createPath("splats/two")
        three <- zk.createPath("splats/three")
      } yield three

      waitForMe.await(1, TimeUnit.SECONDS) should be(true)
      waitForMe.getCount should be(0)
    }
  }
}
