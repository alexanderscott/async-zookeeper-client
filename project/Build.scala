import sbt._
import sbt.Keys._
import scala.Some

object AsyncZkClient extends Build {

  val VERSION = "0.2.3-pc-0.4"

  val dependencies =
    "org.apache.zookeeper" %  "zookeeper"  % "3.4.5" ::
    "org.scalatest"        %% "scalatest"  % "1.9.2" % "test" :: Nil

  val publishDocs = TaskKey[Unit]("publish-docs")

  val project = Project("async-zk-client",file("."),
    settings = Defaults.defaultSettings ++ Seq(
      organization := "com.github.partycoder",
      name         := "async-zk-client",
      version      := VERSION,
      scalaVersion := "2.10.2",

      ivyXML :=
        <dependencies>
          <exclude org="org.jboss.netty" module="netty" />
          <exclude org="com.sun.jmx" module="jmxri" />
          <exclude org="com.sun.jdmk" module="jmxtools" />
          <exclude org="javax.jms" module="jms" />
          <exclude org="thrift" module="libthrift" />
        </dependencies>,

      publishTo := Some(Resolver.file("partycoder.github.com", file(Path.userHome + "/Workspace/repo"))),

      publishDocs <<= ( doc in Compile , target in Compile in doc, version ) map { ( docs, dir, v ) =>
        val newDir = Path.userHome / "/Workspace/repo/docs/async-zk-client" / v
        IO.delete( newDir )
        IO.createDirectory( newDir )
        IO.copyDirectory( dir, newDir )
      },

      libraryDependencies ++= dependencies,

      resolvers += Resolver.url("linter", url("http://hairyfotr.github.io/linteRepo/releases"))
    ))

  addCompilerPlugin("com.foursquare.lint" %% "linter" % "0.1-SNAPSHOT")
}
