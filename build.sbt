ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.16"

// JavaFX ships platform-specific native artifacts, so pick the classifier for the current OS
lazy val osName: String = System.getProperty("os.name").toLowerCase match {
  case n if n.startsWith("linux")   => "linux"
  case n if n.startsWith("mac")     => "mac"
  case n if n.startsWith("windows") => "win"
  case _                            => throw new Exception("Unknown platform!")
}

lazy val javaFXVersion = "21.0.5"
lazy val javaFXModules = Seq("base", "controls", "graphics", "media", "swing")

lazy val root = (project in file("."))
  .settings(
    name := "SimpleMacro",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies ++= Seq(
      "org.scalafx"           %% "scalafx"       % "21.0.0-R32",
      "net.java.dev.jna"       % "jna"           % "5.15.0",
      "net.java.dev.jna"       % "jna-platform"  % "5.15.0",
      "com.github.kwhat"       % "jnativehook"   % "2.2.2",
      "org.openpnp"            % "opencv"        % "4.9.0-0",
      "org.slf4j"              % "slf4j-simple"  % "2.0.16",
      "com.lihaoyi"           %% "ujson"         % "4.4.3",
      "org.scalatest"         %% "scalatest"     % "3.2.19" % Test
    ) ++ javaFXModules.map(m => "org.openjfx" % s"javafx-$m" % javaFXVersion classifier osName),
    Compile / mainClass := Some("ui.Client"),
    run / fork := true
  )
