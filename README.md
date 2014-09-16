Scala Async ZooKeeper Client
----------------------------

This project is a fork of bigtoast's async ZooKeeper client, which is itself a fork of the synchronous
Twitter/Foursquare ZooKeeper client. It adds asynchronous calls and wraps all the calls into futures. This version uses
Scala futures.

<b>Provided features</b>
 * Futures instead of callbacks
 * Relative and Absolute paths
 * Persistent connection (reconnect on expired session)
 * Persistent watches
 * Recursive create and delete

<b>Not provided features</b>
 * Access Control Lists

Currently depends on 
 * Apache ZooKeeper 3.4.6
 * Scala 2.10.3

Getting Started
---------------

build.sbt
```scala

resolvers += "AlexanderScott Repo" at "http://alexanderscott.github.io/maven-repo"

libraryDependencies += "io.github.alexanderscott" %% "async-zk-client" % "0.2.3-ae-0.10"

```

Creating a persistent connection
--------------------------------
This automatically reconnect if the session expires

```scala

import akka.dispatch.ExecutionContext
import com.github.partycoder.zookeeper._
import java.util.concurrent.Executors
import org.apache.zookeeper.Watcher.Event.KeeperState

/* This constructor will block until a connection is made successfully and the
 * client receives a SyncConnected event
 */
val zk = new AsyncZooKeeperClient(
    servers = "127.0.0.1:2181,127.0.0.1:2182",
    sessionTimeout = 4000,
    connectTimeout = 4000,

    /** All paths not starting with '/' will have the base
      * path prepended. Absolute paths, those starting with '/',
      * will ignore this base path.
      */
    basePath = "/death/to/false/metal",

    /** Or use the default ctx from your ActorSystem if you are using Akka already. */
    eCtx = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool))

```

Basic Use
---------
Getting data, getting children, setting, creating and deleting works just like the normal async api
except that paths can be absolute or relative and instead of passing in annoying callbacks
we get rad composable futures.


```scala

/* Create a node ("/death/to/false/metal/newPath") with no data returning a Future[StringResponse] */
zk.create("newPath", None, CreateMode.EPHEMERAL_SEQUENTIAL )

/* Set some data returning a Future[StatResponse] */
zk.set("/death/to/false/metal/newPath",Some("chubbs".getBytes))

/* Get data returning a Future[DataResponse] */
zk.get("newPath")

/* Delete data returning a Future[VoidResponse] */
zk.delete("newPath")

/* compose all these */
for {
  strResp  <- zk.create("newPath", None, EPHEMERAL_SEQUENTIAL )
  statResp <- zk.set(strResp.path, Some("blubbs".getBytes) )
  dataResp <- zk.get(statResp.path)
  voidResp <- zk.delete(dataResp.path, dataResp.stat.getVersion )
} yield voidResp.path + " successfully deleted!!"


```

Additional Helpers
------------------
There are helper methods to recursively create nodes, recursively delete nodes, create nodes returning data
and create a node if it doesn't exist or return it if it already does

```scala

/* create a path returning a Future[VoidResponse] */
zk.createPath("/a/really/long/path")

/* create a node and return a Future[DataResponse]. This is useful if you want the Stat object */
zk.createAndGet("path", None, CreateMode.PERSISTENT )

/* create a node if it doesn't already exist, if it does return what is there. Returns Future[DataResponse] */
zk.getOrCreate("path", Some("blabbs".getBytes), CreateMode.PERSISTENT)

/* if we have paths: "path/a", "path/b", "path/b/c", this will delete "a", "b", "b/c" but will leave "path". Returns Future[VoidResponse] */
zk.deleteChildren("path")

/* same as above but "path" will be deleted as well */
zk.delete("path", force = true )


```

Persistent Watches
------------------
There are also helpers for setting persistent watches.

```scala

/* sets a persistent watch listening for connection events ( connected, disconnected etc.. ). KeeperState is passed
 * into the closure
 */
zk.watchConnection {
    case KeeperState.Expired =>
        // the client will automatically reconnect if the ZK session expires but
        // you can use this to register a callback if you need to.

    case _ =>
}

/** sets a persistent watch on '/death/to/false/metal/parent'. When triggered, getChildren
  * is called, resetting the watch. The ChildrenResponse is passed into the closure.
  *
  * returns a Future[ChildResponse] with the initial children of the parent node
  */
val initialKids = zk.watchChildren("parent"){ kids => /* do stuff with child response */ }

/** sets a persistent watch, returning the initial data at for that node. When the
  * watch is triggered a ( String, Option[DataResponse] ) is passed into the closure
  * and the watch is reset.
  */
val initData = zk.watchData("/some/path/with/data"){
                   case ( path, Some( data ) ) => // do something when the data changed
                   case ( path, None ) => // do something when the node is deleted
               }

// By default watchChildren and watchData set persistent watches, but you can set one time watches thusly
zk.watchChildren("/some/parent",false){ kids => /* triggered just once */ }

zk.watchData("/some/node/with/data", false){ case ( path, dataOp ) => /* triggered just once */ }

```
