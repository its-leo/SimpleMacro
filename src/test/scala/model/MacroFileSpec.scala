package model

import model.MacroAction._
import org.scalatest.funsuite.AnyFunSuite

import java.awt.Color
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files

class MacroFileSpec extends AnyFunSuite {

  private val image = {
    val img = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB)
    img.setRGB(3, 4, Color.RED.getRGB)
    img
  }

  private val actions: Seq[MacroAction] = Seq(
    ClickPosition(WindowPoint(Point(10, 20), Size(800, 600)), ClickSettings(MouseButton.Middle, 250, 2, 1.5)),
    ClickVisual(image, VisualSearch(0.9, 7000, multiScale = true), ClickSettings(MouseButton.Right)),
    Wait(3725),
    TypeText("Hällo \"Welt\"\n€"),
    KeyCombination(KeyEvent.VK_S, "S", ctrl = true, shift = true),
    Scroll(-5, Some(WindowPoint(Point(1, 2), Size(3, 4)))),
    Scroll(2),
    Drag(Point(1, 2), Point(30, 40), Size(500, 400), MouseButton.Left, 0.7),
    WaitForImage(image, appear = false, VisualSearch(0.8, 60000))
  )

  /** Images are compared by content, everything else by equality. */
  private def normalize(action: MacroAction): Any = action match {
    case a: ClickVisual => (a.copy(image = null), pixels(a.image))
    case a: WaitForImage => (a.copy(image = null), pixels(a.image))
    case other => other
  }

  private def pixels(img: BufferedImage) = (img.getWidth, img.getHeight, img.getRGB(0, 0, img.getWidth, img.getHeight, null, 0, img.getWidth).toSeq)

  test("all action types survive a JSON round trip") {
    val restored = MacroFile.fromJson(MacroFile.toJson(actions))
    assert(restored.map(normalize) == actions.map(normalize))
  }

  test("save and load a file") {
    val file = Files.createTempFile("macro", ".smacro").toFile
    try {
      MacroFile.save(file, actions)
      assert(MacroFile.load(file).map(normalize) == actions.map(normalize))
    } finally file.delete()
  }

  test("invalid files are rejected with a helpful message") {
    val notJson = intercept[MacroFile.MacroFormatException](MacroFile.fromJson("not json"))
    assert(notJson.getMessage.contains("invalid JSON"))

    val otherFormat = intercept[MacroFile.MacroFormatException](MacroFile.fromJson("""{"format":"other","version":1,"actions":[]}"""))
    assert(otherFormat.getMessage.contains("not a SimpleMacro file"))

    val newer = intercept[MacroFile.MacroFormatException](MacroFile.fromJson("""{"format":"simplemacro","version":99,"actions":[]}"""))
    assert(newer.getMessage.contains("newer version"))

    val badAction = intercept[MacroFile.MacroFormatException](MacroFile.fromJson("""{"format":"simplemacro","version":1,"actions":[{"type":"wait","seconds":1},{"type":"teleport"}]}"""))
    assert(badAction.getMessage.contains("Action 2"))
  }

  test("summaries describe the actions") {
    assert(KeyCombination(KeyEvent.VK_S, "S", ctrl = true, shift = true).summary == "Ctrl+Shift+S")
    assert(Scroll(-3).summary == "up 3")
    assert(ClickPosition(WindowPoint(Point(1, 2), Size(3, 4)), ClickSettings(MouseButton.Right, clicks = 2)).summary == "(1, 2) right double")
    assert(Wait(90).summary == "1 minute 30 seconds")
  }
}
