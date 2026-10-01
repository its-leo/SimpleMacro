package engine

import model.MouseButton

import java.awt.image.BufferedImage
import java.awt.{Point, Rectangle}
import scala.collection.mutable

/** Records input events and serves a fixed screen image. */
class FakeDesktop(var screen: BufferedImage = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)) extends Desktop {
  val events: mutable.Buffer[String] = mutable.ArrayBuffer.empty[String]
  private var cursor = new Point(0, 0)

  def windows(): Seq[WindowEntry] = Seq.empty

  def virtualScreen: Rectangle = new Rectangle(0, 0, screen.getWidth, screen.getHeight)

  def cursorPosition: Point = new Point(cursor)

  def moveCursor(x: Int, y: Int): Unit = cursor = new Point(x, y)

  def mousePress(button: MouseButton): Unit = events += s"press ${button.id} at ${cursor.x},${cursor.y}"

  def mouseRelease(button: MouseButton): Unit = events += s"release ${button.id} at ${cursor.x},${cursor.y}"

  def keyPress(keyCode: Int): Unit = events += s"key down $keyCode"

  def keyRelease(keyCode: Int): Unit = events += s"key up $keyCode"

  def scroll(notches: Int): Unit = events += s"scroll $notches"

  def paste(text: String): Unit = events += s"paste $text"

  def capture(area: Rectangle): BufferedImage = synchronized {
    val image = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.drawImage(screen, -area.x, -area.y, null)
    g.dispose()
    image
  }
}

class FakeWindow(var area: Rectangle, val title: String = "Test Window") extends TargetWindow {
  var open = true
  val resizes: mutable.Buffer[(Int, Int)] = mutable.ArrayBuffer.empty

  def isOpen: Boolean = open

  def bounds: Rectangle = new Rectangle(area)

  def clientArea: Rectangle = new Rectangle(area)

  def resize(width: Int, height: Int): Unit = {
    resizes += ((width, height))
    area = new Rectangle(area.x, area.y, width, height)
  }

  def toFront(): Unit = ()

  def sameWindowAs(other: TargetWindow): Boolean = other eq this
}
