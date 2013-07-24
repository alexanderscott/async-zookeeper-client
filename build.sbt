organization := "com.github.partycoder"

name := "async-zk-client"

version := "0.2.3-pc"

scalaVersion := "2.10.2"

resolvers ++= Seq(
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Sonatype Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
  "Sonatype Releases" at "http://oss.sonatype.org/content/repositories/releases",
  "repo1" at "http://repo1.maven.org/maven2/",
  "jboss-repo" at "http://repository.jboss.org/maven2/",
  "apache" at "http://people.apache.org/repo/m2-ibiblio-rsync-repository/"
)

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest"  % "1.9.1" % "test",
  "org.slf4j" % "slf4j-api" % "1.6.4",
  "org.slf4j" % "slf4j-log4j12" % "1.6.4",
  "log4j" % "log4j" % "1.2.16",
  "org.apache.zookeeper" % "zookeeper" % "3.4.3" excludeAll(
     ExclusionRule(name = "jms"),
     ExclusionRule(name = "jmxtools"),
     ExclusionRule(name = "jmxri")
  )
)

licenses += ("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0.txt</url>"))