ThisBuild / version      := "0.2.0"
ThisBuild / scalaVersion := "2.13.16"

// JavaFX ships platform-specific native artifacts, so pick the classifier for the current OS
lazy val osName: String = System.getProperty("os.name").toLowerCase match {
  case n if n.startsWith("linux")   => "linux"
  case n if n.startsWith("mac")     => "mac"
  case n if n.startsWith("windows") => "win"
  case _                            => throw new Exception("Unknown platform!")
}

// Native library folders of OpenCV and JNA for the current platform; the others are left out of the assembly jar
lazy val openCvOs: String = osName match {
  case "win" => "windows"
  case "mac" => "osx"
  case other => other
}
lazy val jnaPlatform: String = osName match {
  case "win" => "win32-x86-64"
  case "mac" => "darwin"
  case _     => "linux-x86-64"
}

lazy val javaFXVersion = "21.0.5"
// "media" is required by ScalaFX's Stage implementation even though SimpleMacro plays no media
lazy val javaFXModules = Seq("base", "controls", "graphics", "media", "swing")

lazy val root = (project in file("."))
  .settings(
    name := "SimpleMacro",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies ++= Seq(
      // ScalaFX depends on all JavaFX modules; WebKit (100 MB) and FXML are not used
      ("org.scalafx"          %% "scalafx"       % "21.0.0-R32").excludeAll(
        ExclusionRule("org.openjfx", "javafx-web"),
        ExclusionRule("org.openjfx", "javafx-fxml")
      ),
      "net.java.dev.jna"       % "jna"           % "5.15.0",
      "net.java.dev.jna"       % "jna-platform"  % "5.15.0",
      "com.github.kwhat"       % "jnativehook"   % "2.2.2",
      "org.openpnp"            % "opencv"        % "4.9.0-0",
      "org.slf4j"              % "slf4j-simple"  % "2.0.16",
      "com.lihaoyi"           %% "ujson"         % "4.4.3",
      "org.scalatest"         %% "scalatest"     % "3.2.19" % Test
    ) ++ javaFXModules.map(m => "org.openjfx" % s"javafx-$m" % javaFXVersion classifier osName),
    Compile / mainClass := Some("ui.Client"),
    run / fork := true,

    // Single executable jar (sbt assembly), packaged as a Windows application by the CI workflow
    assembly / assemblyJarName := "SimpleMacro.jar",
    assembly / test := {},
    assembly / assemblyMergeStrategy := {
      case PathList("module-info.class") => MergeStrategy.discard
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case PathList("META-INF", "substrate", _*) => MergeStrategy.discard
      case PathList("nu", "pattern", "opencv", os, arch, _*) if os != openCvOs || arch != "x86_64" => MergeStrategy.discard
      case PathList("com", "sun", "jna", platform, _) if platform.contains("-") && !platform.startsWith(jnaPlatform) => MergeStrategy.discard
      case other => (assembly / assemblyMergeStrategy).value(other)
    }
  )
