
val indi = (project in (file(".")))
  .settings(
    scalaVersion := "2.12.10"
  )
  .settings(
    libraryDependencies += "org.typelevel" %% "cats-effect" % "2.0.0",
    libraryDependencies += "org.typelevel" %% "simulacrum" % "1.0.0",
     // For Scala 2.11-2.12
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full),
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.0" % Test
  )

