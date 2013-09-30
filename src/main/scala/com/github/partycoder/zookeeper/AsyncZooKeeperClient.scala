package com.github.partycoder.zookeeper

import java.util
import java.util.concurrent.{TimeUnit, CountDownLatch}
import org.apache.zookeeper._
import org.apache.zookeeper.AsyncCallback._
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.KeeperException.Code
import org.apache.zookeeper.Watcher.Event.{EventType, KeeperState}
import org.apache.zookeeper.ZooDefs.Ids
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, ExecutionContext, Promise}
import scala.util.{Failure, Success}

object AsyncZooKeeperClient {
  def deSerializeString(array: Array[Byte]): String = {
    array.view.map(_.toChar).mkString
  }
}

/**
 * Scala wrapper around the async ZK api. This is indirectly based on the bigtoast fork of the Twitter/Foursquare
 * Scala wrapper. The reason for the fork was mainly to use the latest Akka.
 * https://github.com/bigtoast/async-zookeeper-client
 * https://github.com/foursquare/scala-zookeeper-client
 *
 * bigtoast's version doesn't implement ACL and neither do we.
 *
 * You can pass in a base path (defaults to "/") which will be prepended to any path that does not start with a "/".
 * This allows you to specify a context to all requests. Absolute paths, those starting with "/", will not have the
 * base path prepended.
 *
 * On connection the base path will be created if it does not already exist.
 */
class AsyncZooKeeperClient(val servers: String, val sessionTimeout: Int, val connectTimeout: Int,
                           val basePath: String, watcher: Option[AsyncZooKeeperClient => Unit], eCtx: ExecutionContext) {
  import AsyncResponse._

  implicit val c = eCtx
  private val log = LoggerFactory.getLogger(this.getClass)

  @volatile private var clientWatcher: Option[Watcher] = None
  @volatile private var zk: ZooKeeper = null

  def this(servers: String, sessionTimeout: Int, connectTimeout: Int, basePath: String, eCtx: ExecutionContext) = {
    this(servers, sessionTimeout, connectTimeout, basePath, None, eCtx)
  }

  def this(servers: String, sessionTimeout: Int, connectTimeout: Int,
           basePath: String, watcher: AsyncZooKeeperClient => Unit, eCtx: ExecutionContext) = {
    this(servers, sessionTimeout, connectTimeout, basePath, Some(watcher), eCtx)
  }

  def this(servers: String, eCtx: ExecutionContext) = {
    this(servers, 3000, 3000, "/", None, eCtx)
  }

  def this(servers: String, watcher: AsyncZooKeeperClient => Unit, eCtx: ExecutionContext) = {
    this(servers, 3000, 3000, "/", watcher, eCtx)
  }

  /**
   * Get the underlying ZK connection
   **/
  def underlying: Option[ZooKeeper] = {
    Option(zk)
  }

  /**
   * Connect() attaches to the remote zookeeper and sets an instance variable.
   */
  private[zookeeper] def connect(): Unit = {
    import KeeperState._
    val connectionLatch = new CountDownLatch(1)
    val assignLatch = new CountDownLatch(1)

    if (zk != null) {
      zk.close()
    }

    zk = new ZooKeeper(servers, sessionTimeout, new Watcher {
      def process(event: WatchedEvent) = {
        assignLatch.await()
        event.getState match {
          case SyncConnected => connectionLatch.countDown()
          case Expired => connect()
          case _ =>
        }

        clientWatcher.foreach {
          _.process(event)
        }
      }
    })

    assignLatch.countDown()
    log.info("Attempting to connect to zookeeper servers {}", servers)
    connectionLatch.await(sessionTimeout, TimeUnit.MILLISECONDS)
    try {
      isAliveSync
      Await.result(createPath(""), 10 seconds)
    } catch {
      case e: Throwable => {
        log.error("Could not connect to zookeeper ensemble: " + servers
          + ". Connection timed out after " + connectTimeout + " milliseconds!", e)

        throw new RuntimeException("Could not connect to zookeeper ensemble: " + servers
          + ". Connection timed out after " + connectTimeout + " milliseconds!", e)
      }
    }
  }

  private[zookeeper] def handleNull(op: Option[Array[Byte]]): Array[Byte] = {
    if (op == null) null else op.getOrElse(null)
  }

  /**
   * Given a string representing a path, return each subpath
   * Ex. subPaths("/a/b/c", "/") == ["/a", "/a/b", "/a/b/c"]
   */
  def subPaths(path: String, sep: Char) = {
    path.split(sep).toList match {
      case Nil => Nil
      case l :: tail => {
        tail.foldLeft[List[String]](Nil) {
          (xs, x) =>
            (xs.headOption.getOrElse("") + sep.toString + x) :: xs
        }.reverse
      }
    }
  }

  /**
   * Create a zk path from a relative path. If an absolute path is passed in the base path will not be prepended.
   * Trailing '/'s will be stripped except for the path '/' and all '//' will be translated to '/'.
   *
   * @param path relative or absolute path
   * @return absolute zk path
   */
  private[zookeeper] def mkPath(path: String) = (path startsWith "/" match {
    case true => {
      path
    }
    case false => {
      "%s/%s".format(basePath, path)
    }
  }).replaceAll("//", "/") match {
    case str if str.length > 1 => {
      str.stripSuffix("/")
    }
    case str => {
      str
    }
  }

  /**
   * Helper method to convert a zk response in to a client reponse and handle the errors
   **/
  def handleResponse[T](rc: Int, path: String, p: Promise[T], stat: Stat, cxt: Option[Any])(f: => T): Future[T] = {
    Code.get(rc) match {
      case Code.OK => {
        p.success(f).future
      }
      case error if path == null => {
        p.failure(FailedAsyncResponse(KeeperException.create(error), Option(path), Option(stat), cxt)).future
      }
      case error => {
        p.failure(FailedAsyncResponse(KeeperException.create(error, path), Option(path), Option(stat), cxt)).future
      }
    }
  }

  /**
   * Wrapper around the ZK exists method. Watch is hardcoded to false.
   *
   * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#exists(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.StatCallback, java.lang.Object)">
   *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#exists(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.StatCallback, java.lang.Object)</a>
   */
  def exists(path: String, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[StatResponse] = {
    val p = Promise[StatResponse]()
    zk.exists(mkPath(path), watch.getOrElse(null), new StatCallback {
      def processResult(rc: Int, path: String, ignore: Any, stat: Stat) {
        handleResponse(rc, path, p, stat, ctx) {
          StatResponse(path, stat, ctx)
        }
      }
    }, ctx)
    p.future
  }

  /**
   * Wrapper around the ZK getChildren method. Watch is hardcoded to false.
   *
   * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#getChildren(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.Children2Callback, java.lang.Object)">
   *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#getChildren(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.Children2Callback, java.lang.Object)</a>
   */
  def getChildren(path: String, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[ChildrenResponse] = {
    val p = Promise[ChildrenResponse]()
    zk.getChildren(mkPath(path), watch.getOrElse(null), new Children2Callback {
      def processResult(rc: Int, path: String, ignore: Any, children: util.List[String], stat: Stat) {
        handleResponse(rc, path, p, stat, ctx) {
          ChildrenResponse(children.toSeq, path, stat, ctx)
        }
      }
    }, ctx)
    p.future
  }

  /**
   * Close the underlying zk connection
   **/
  def close():Unit = {
    zk.close()
  }

  /**
   * Checks the connection by checking existence of "/"
   **/
  def isAlive: Future[Boolean] = exists("/") map {
    _.stat.getVersion >= 0
  }

  /**
   * Check the connection synchronously
   **/
  def isAliveSync: Boolean = try {
    zk.exists("/", false)
    true
  } catch {
    case e: Throwable => {
      log.warn("ZK not connected in isAliveSync", e)
      false
    }
  }

  /**
   * Recursively create a path with persistent nodes and no watches set.
   **/
  def createPath(path: String): Future[VoidResponse] = {
    Future.sequence {
      for {
        subPath <- subPaths(mkPath(path), '/')
      } yield {
        create(subPath, null, CreateMode.PERSISTENT).recover {
          case e: FailedAsyncResponse if e.code == Code.NODEEXISTS => VoidResponse(subPath, None)
        }
      }
    } map {
      _ => VoidResponse(path, None)
    }
  }

  /**
   * Create a path with OPEN_ACL_UNSAFE hardcoded
   * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#create(java.lang.String, byte[], java.util.List, org.apache.zookeeper.CreateMode, org.apache.zookeeper.AsyncCallback.StringCallback, java.lang.Object)">
   *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#create(java.lang.String, byte[], java.util.List, org.apache.zookeeper.CreateMode, org.apache.zookeeper.AsyncCallback.StringCallback, java.lang.Object)
   *      </a>
   */
  def create(path: String, data: Option[Array[Byte]], createMode: CreateMode, ctx: Option[Any] = None): Future[StringResponse] = {
    val p = Promise[StringResponse]()
    zk.create(mkPath(path), handleNull(data), Ids.OPEN_ACL_UNSAFE, createMode, new StringCallback {
      def processResult(rc: Int, path: String, ignore: Any, name: String) {
        handleResponse(rc, path, p, null, ctx) {
          StringResponse(name, path, ctx)
        }
      }
    }, ctx)
    p.future
  }

  /** Create a node and then return it. Under the hood this is a create followed by a get. If the stat or data is not
    * needed use a plain create which is cheaper.
    */
  def createAndGet(path: String, data: Option[Array[Byte]], createMode: CreateMode, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[DataResponse] = {
    create(path, data, createMode, ctx) flatMap {
      _ => get(path, ctx, watch = watch)
    }
  }

  /**
   * Return the node if it exists, otherwise create a new node with the data passed in. If the node is created a get will
   * be called and the value returned. In case of a race condition where a two or more requests are executed at the same
   * time and one of the creates will fail with a NodeExistsException, it will be handled and a get will be called.
   */
  def getOrCreate(path: String, data: Option[Array[Byte]], createMode: CreateMode, ctx: Option[Any] = None): Future[DataResponse] = {
    get(path) recoverWith {
      case FailedAsyncResponse(e: KeeperException.NoNodeException, _, _, _) => {
        create(path, data, createMode, ctx) flatMap {
          _ => get(path)
        } recoverWith {
          case FailedAsyncResponse(e: KeeperException.NodeExistsException, _, _, _) =>
            get(path)
        }
      }
    }
  }

  /** Wrapper around zk getData method. Watch is hardcoded to false.
    * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#getData(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.DataCallback, java.lang.Object)">
    *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#getData(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.DataCallback, java.lang.Object)
    *      </a>
    */
  def get(path: String, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[DataResponse] = {
    val p = Promise[DataResponse]()
    zk.getData(mkPath(path), watch.getOrElse(null), new DataCallback {
      def processResult(rc: Int, path: String, ignore: Any, data: Array[Byte], stat: Stat) {
        handleResponse(rc, path, p, stat, ctx) {
          DataResponse(Option(data), path, stat, ctx)
        }
      }
    }, ctx)
    p.future
  }

  /** Wrapper around the zk setData method.
    * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#setData(java.lang.String, byte[], int, org.apache.zookeeper.AsyncCallback.StatCallback, java.lang.Object)">
    *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#setData(java.lang.String, byte[], int, org.apache.zookeeper.AsyncCallback.StatCallback, java.lang.Object)
    *      </a>
    */
  def set(path: String, data: Option[Array[Byte]], version: Int = -1, ctx: Option[Any] = None): Future[StatResponse] = {
    val p = Promise[StatResponse]()
    zk.setData(mkPath(path), handleNull(data), version, new StatCallback {
      def processResult(rc: Int, path: String, ignore: Any, stat: Stat) {
        handleResponse(rc, path, p, stat, ctx) {
          StatResponse(path, stat, ctx)
        }
      }
    }, ctx)
    p.future
  }

  /**
   * Wrapper around zk delete method.
   * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#delete(java.lang.String, int, org.apache.zookeeper.AsyncCallback.VoidCallback, java.lang.Object)">
   *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#delete(java.lang.String, int, org.apache.zookeeper.AsyncCallback.VoidCallback, java.lang.Object)
   *      </a>
   *
   *      The version only applies to the node indicated by the path. If force is true all children will be deleted even if the
   *      version doesn't match.
   *
   * @param force Delete all children of this node
   */
  def delete(path: String, version: Int = -1, ctx: Option[Any] = None, force: Boolean = false): Future[VoidResponse] = {
    if (force) {
      deleteChildren(path, ctx) flatMap {
        _ => delete(path, version, ctx, force = false)
      }
    } else {
      val p = Promise[VoidResponse]()
      zk.delete(mkPath(path), version, new VoidCallback {
        def processResult(rc: Int, path: String, ignore: Any) {
          handleResponse(rc, path, p, null, ctx) {
            VoidResponse(path, ctx)
          }
        }
      }, ctx)

      p.future
    }
  }

  /**
   * Delete all the children of a node but not the node.
   **/
  def deleteChildren(path: String, ctx: Option[Any] = None): Future[VoidResponse] = {
    def recurse(p: String): Future[VoidResponse] = {
      getChildren(p) flatMap {
        response => {
          Future.sequence {
            response.children.map {
              child => recurse(mkPath(p) + "/" + child)
            }
          }
        }
      } flatMap {
        seq =>
          if (p == path) {
            Future.successful(VoidResponse(path, ctx))
          } else {
            delete(p, -1, ctx)
          }
      }
    }

    recurse(path)
  }

  /**
   * Set a persistent watch on data.
   * @see watchData
   */
  def watchData(path: String)(onData: (String, Option[DataResponse]) => Unit): Future[DataResponse] = {
    watchData(path, persistent = true)(onData)
  }

  /** set a persistent watch on a node listening for data changes. If a NodeDataChanged event is received or a
    * NodeCreated event is received the DataResponse will be returned with the new data and the watch will be
    * reset. If a None or NodeChildrenChanged the watch will be reset returning nothing. If the node is deleted
    * receiving a NodeDeleted event None will be returned.
    *
    * In the event of an error nothing will be returned but errors will be logged.
    *
    * @param path       relative or absolute
    * @param persistent if this is false the watch will not be reset after receiving an event. ( normal zk behavior )
    * @param onData     callback executed on data events. None on node deleted event. path will always be absolute regardless
    *                   of what is passed into the function
    * @return initial data. If this returns successfully the watch was set otherwise it wasn't
    */
  def watchData(path: String, persistent: => Boolean)(onData: (String, Option[DataResponse]) => Unit): Future[DataResponse] = {
    val w = new Watcher {

      def ifPersist: Option[Watcher] = {
        if (persistent) Some(this) else None
      }

      def process(event: WatchedEvent) = event.getType match {
        case e if e == EventType.None || e == EventType.NodeChildrenChanged => {
          exists(path, watch = ifPersist)
        }

        case e if e == EventType.NodeCreated || e == EventType.NodeDataChanged => {
          get(path, watch = ifPersist).onComplete {
            case Failure(error) => {
              log.error("Error on NodeCreated callback for path %s".format(mkPath(path)), error)
            }
            case Success(data) => {
              onData(mkPath(path), Some(data))
            }
          }
        }

        case EventType.NodeDeleted => {
          get(path, watch = ifPersist) onComplete {
            case Failure(error: FailedAsyncResponse) if error.code == Code.NONODE => {
              onData(mkPath(path), None)
            }
            case Success(data) => {
              onData(mkPath(path), Some(data))
            }
            case Failure(error) => {
              log.error("Error on NodeCreated callback for path %s".format(mkPath(path)), error)
            }
          }
        }
      }
    }

    get(path, watch = Some(w))
  }

  /** Sets a persistent child watch.
    * @see watchChildren
    */
  def watchChildren(path: String)(onKids: ChildrenResponse => Unit): Future[ChildrenResponse] = {
    watchChildren(path, persistent = true)(onKids)
  }

  /**
   * Set a persistent watch on if the children of this node change. If they do the updated ChildResponse will be returned.
   */
  def watchChildren(path: String, persistent: => Boolean)(onKids: ChildrenResponse => Unit): Future[ChildrenResponse] = {
    val p = mkPath(path)
    val w = new Watcher {
      def ifPersist: Option[Watcher] = {
        if (persistent) Some(this) else None
      }

      def process(event: WatchedEvent) = event.getType match {
        case EventType.NodeChildrenChanged => {
          getChildren(p, watch = ifPersist) onComplete {
            case Failure(error) => {
              log.error("Error on NodeChildrenChanged callback for path %s".format(p), error)
            }
            case Success(kids) => {
              onKids(kids)
            }
          }
          exists(p, watch = ifPersist)
        }

        case e: Watcher.Event.EventType => {
          log.error("Not expecting to get event %s in a watchChildren" format e)
        }
      }
    }

    getChildren(p, watch = Some(w))
  }

  /**
   * sets the client watcher. reconnecting on session expired is handled by the client and doesn't need to be handled
   * here.
   */
  def watchConnection(onState: KeeperState => Unit) = {
    val w = new Watcher {
      def process(event: WatchedEvent) = onState(event.getState)
    }

    clientWatcher = Some(w)
  }

  connect()
}