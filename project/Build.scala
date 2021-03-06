import sbt._
import sbt.Keys._
import scala.Some

object AsyncZkClient extends Build {

  val VERSION = "0.2.3-ae-0.10"

  val dependencies = Seq(
    "org.apache.zookeeper" %  "zookeeper"  % "3.4.6" excludeAll (
                                                        ExclusionRule(organization = "log4j"),
                                                        ExclusionRule(organization = "org.slf4j")
                                                     ),
    "org.slf4j"            %  "log4j-over-slf4j"   % "1.7.5",
    "org.scalatest"        %% "scalatest"  % "2.1.4" % "test",
    "org.slf4j"            %  "slf4j-simple"       % "1.7.5" % "test"
  )

  val publishDocs = TaskKey[Unit]("publish-docs")

  val project = Project("async-zk-client",file("."),
    settings = Defaults.defaultSettings ++ Seq(
      organization := "io.github.alexanderscott",
      name         := "async-zk-client",
      version      := VERSION,
      scalaVersion := "2.10.3",


      publishTo := Some(Resolver.file("alexanderscott.github.io", file(Path.userHome + "/Developer/alex/maven-repo"))),

      publishDocs <<= ( doc in Compile , target in Compile in doc, version ) map { ( docs, dir, v ) =>
        val newDir = Path.userHome / "/Developer/alex/maven-repo/docs/async-zk-client" / v
        IO.delete( newDir )
        IO.createDirectory( newDir )
        IO.copyDirectory( dir, newDir )
      },

      libraryDependencies ++= dependencies,

      resolvers += Resolver.url("linter", url("http://hairyfotr.github.io/linteRepo/releases"))
    ))

  addCompilerPlugin("com.foursquare.lint" %% "linter" % "0.1-SNAPSHOT")
}
