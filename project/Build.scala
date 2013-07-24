
import sbt._
import Keys._
import Process._

object AsyncZkClient extends Build {

  val VERSION = "0.2.3-pc"

  val dependencies =
    "org.apache.zookeeper" %  "zookeeper"  % "3.4.3" ::
    "org.scalatest"        %% "scalatest"  % "1.9.1" % "test" :: Nil

  val publishDocs = TaskKey[Unit]("publish-docs")

  val project = Project("async-zk-client",file("."),
    settings = Defaults.defaultSettings ++ Seq(
      organization := "com.github.partycoder",
      name         := "async-zk-client",
      version      := VERSION,
      scalaVersion := "2.10.2",

      ivyXML :=
        <dependencies>
          <exclude org="com.sun.jmx" module="jmxri" />
          <exclude org="com.sun.jdmk" module="jmxtools" />
          <exclude org="javax.jms" module="jms" />
          <exclude org="thrift" module="libthrift" />
        </dependencies>,

      publishTo := Some(Resolver.file("partycoder.github.com", file(Path.userHome + "/Workspace/GitHub repo"))),

      publishDocs <<= ( doc in Compile , target in Compile in doc, version ) map { ( docs, dir, v ) =>
        val newDir = Path.userHome / "/Workspace/GitHub repo/docs/async-zk-client" / v
        IO.delete( newDir )
        IO.createDirectory( newDir )
        IO.copyDirectory( dir, newDir )
      },

      libraryDependencies ++= dependencies
    ))
}
