package model

import model.MacroAction._

import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import java.util.Base64
import javax.imageio.ImageIO
import scala.util.Try

/** Reads and writes macros as JSON. Images are embedded as base64 encoded PNGs. */
object MacroFile {

  val Format = "simplemacro"
  val Version = 1
  val Extension = "smacro"

  def toJson(actions: Seq[MacroAction]): String =
    ujson.write(ujson.Obj(
      "format" -> Format,
      "version" -> Version,
      "actions" -> ujson.Arr(actions.map(encode): _*)
    ), indent = 2)

  def fromJson(json: String): Seq[MacroAction] = {
    val root = Try(ujson.read(json)).getOrElse(throw new MacroFormatException("The file is not a valid macro file (invalid JSON)."))
    if (root.objOpt.flatMap(_.get("format")).flatMap(_.strOpt).forall(_ != Format))
      throw new MacroFormatException("The file is not a SimpleMacro file.")
    val version = root("version").num.toInt
    if (version > Version)
      throw new MacroFormatException(s"The file was created by a newer version of SimpleMacro (format version $version).")
    root("actions").arr.toSeq.zipWithIndex.map { case (value, index) =>
      Try(decode(value)).fold(e => throw new MacroFormatException(s"Action ${index + 1} is invalid: ${e.getMessage}"), identity)
    }
  }

  def save(file: File, actions: Seq[MacroAction]): Unit = {
    // Write to a temporary file first so a failed write never destroys an existing macro
    val target = file.toPath.toAbsolutePath
    Option(target.getParent).foreach(Files.createDirectories(_))
    val temp = Files.createTempFile(target.getParent, ".simplemacro", ".tmp")
    try {
      Files.write(temp, toJson(actions).getBytes(StandardCharsets.UTF_8))
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    } finally Files.deleteIfExists(temp)
  }

  def load(file: File): Seq[MacroAction] =
    fromJson(new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8))

  /** Location of the automatically saved macro, restored on the next start. */
  def autosaveFile: File = new File(new File(System.getProperty("user.home"), ".simplemacro"), s"autosave.$Extension")

  class MacroFormatException(message: String) extends Exception(message)

  // ---------------------------------------------------------------------------

  private def encode(action: MacroAction): ujson.Value = action match {
    case ClickPosition(target, settings) =>
      ujson.Obj("type" -> "clickPosition", "target" -> encodeWindowPoint(target), "click" -> encodeClick(settings))
    case ClickVisual(image, search, settings) =>
      ujson.Obj("type" -> "clickVisual", "image" -> encodeImage(image), "search" -> encodeSearch(search), "click" -> encodeClick(settings))
    case Wait(seconds) =>
      ujson.Obj("type" -> "wait", "seconds" -> seconds)
    case TypeText(text) =>
      ujson.Obj("type" -> "typeText", "text" -> text)
    case k: KeyCombination =>
      ujson.Obj("type" -> "keyCombination", "keyCode" -> k.keyCode, "keyName" -> k.keyName,
        "ctrl" -> k.ctrl, "alt" -> k.alt, "shift" -> k.shift, "meta" -> k.meta)
    case Scroll(amount, target) =>
      val obj = ujson.Obj("type" -> "scroll", "amount" -> amount)
      target.foreach(t => obj("target") = encodeWindowPoint(t))
      obj
    case Drag(from, to, windowSize, button, mouseSpeed) =>
      ujson.Obj("type" -> "drag", "from" -> encodePoint(from), "to" -> encodePoint(to), "windowSize" -> encodeSize(windowSize),
        "button" -> button.id, "mouseSpeed" -> mouseSpeed)
    case WaitForImage(image, appear, search) =>
      ujson.Obj("type" -> "waitForImage", "image" -> encodeImage(image), "appear" -> appear, "search" -> encodeSearch(search))
  }

  private def decode(v: ujson.Value): MacroAction = v("type").str match {
    case "clickPosition" => ClickPosition(decodeWindowPoint(v("target")), decodeClick(v("click")))
    case "clickVisual" => ClickVisual(decodeImage(v("image")), decodeSearch(v("search")), decodeClick(v("click")))
    case "wait" => Wait(v("seconds").num.toInt)
    case "typeText" => TypeText(v("text").str)
    case "keyCombination" =>
      KeyCombination(v("keyCode").num.toInt, v("keyName").str, v("ctrl").bool, v("alt").bool, v("shift").bool, v("meta").bool)
    case "scroll" => Scroll(v("amount").num.toInt, v.obj.get("target").map(decodeWindowPoint))
    case "drag" => Drag(decodePoint(v("from")), decodePoint(v("to")), decodeSize(v("windowSize")), MouseButton.fromId(v("button").str), v("mouseSpeed").num)
    case "waitForImage" => WaitForImage(decodeImage(v("image")), v("appear").bool, decodeSearch(v("search")))
    case other => throw new MacroFormatException(s"unknown action type '$other'")
  }

  private def encodePoint(p: Point) = ujson.Obj("x" -> p.x, "y" -> p.y)

  private def decodePoint(v: ujson.Value) = Point(v("x").num.toInt, v("y").num.toInt)

  private def encodeSize(s: Size) = ujson.Obj("width" -> s.width, "height" -> s.height)

  private def decodeSize(v: ujson.Value) = Size(v("width").num.toInt, v("height").num.toInt)

  private def encodeWindowPoint(w: WindowPoint) = ujson.Obj("position" -> encodePoint(w.position), "windowSize" -> encodeSize(w.windowSize))

  private def decodeWindowPoint(v: ujson.Value) = WindowPoint(decodePoint(v("position")), decodeSize(v("windowSize")))

  private def encodeClick(c: ClickSettings) =
    ujson.Obj("button" -> c.button.id, "delayAfterMs" -> c.delayAfterMs, "clicks" -> c.clicks, "mouseSpeed" -> c.mouseSpeed)

  private def decodeClick(v: ujson.Value) =
    ClickSettings(MouseButton.fromId(v("button").str), v("delayAfterMs").num.toInt, v("clicks").num.toInt, v("mouseSpeed").num)

  private def encodeSearch(s: VisualSearch) =
    ujson.Obj("threshold" -> s.threshold, "timeoutMs" -> s.timeoutMs, "multiScale" -> s.multiScale)

  private def decodeSearch(v: ujson.Value) =
    VisualSearch(v("threshold").num, v("timeoutMs").num.toInt, v("multiScale").bool)

  private def encodeImage(image: BufferedImage): String = {
    val out = new ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    Base64.getEncoder.encodeToString(out.toByteArray)
  }

  private def decodeImage(v: ujson.Value): BufferedImage =
    Option(ImageIO.read(new ByteArrayInputStream(Base64.getDecoder.decode(v.str))))
      .getOrElse(throw new MacroFormatException("image could not be decoded"))
}
