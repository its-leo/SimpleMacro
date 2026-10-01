package ui

import engine.{Desktop, TargetWindow, WindowEntry}
import model.MouseButton

import java.awt.image.BufferedImage
import java.awt.{BasicStroke, Color, Font, Point, Rectangle, RenderingHints}

/** A simulated desktop with a few windows and a music player UI, used to create the README screenshots. */
class DemoDesktop extends Desktop {

  val player = new DemoWindow("Discover Weekly - Music Player", new Rectangle(160, 90, 1200, 800))

  private val windowList: Seq[(TargetWindow, Color, String)] = Seq(
    (player, new Color(30, 185, 84), "♪"),
    (new DemoWindow("Inbox - Mail", new Rectangle(0, 0, 1000, 700)), new Color(0, 120, 212), "@"),
    (new DemoWindow("Quarterly Report.xlsx - Spreadsheet", new Rectangle(0, 0, 1000, 700)), new Color(33, 115, 70), "X"),
    (new DemoWindow("notes.txt - Editor", new Rectangle(0, 0, 1000, 700)), new Color(96, 125, 139), "N"),
    (new DemoWindow("Downloads - File Explorer", new Rectangle(0, 0, 1000, 700)), new Color(255, 193, 7), "F"),
    (new DemoWindow("Weather Forecast - Web Browser", new Rectangle(0, 0, 1000, 700)), new Color(244, 81, 30), "W"),
    (new DemoWindow("Calculator", new Rectangle(0, 0, 400, 600)), new Color(94, 53, 177), "="),
  )

  def windows(): Seq[WindowEntry] = windowList.map { case (w, color, letter) => WindowEntry(w, Some(appIcon(color, letter))) }

  private def appIcon(color: Color, letter: String): BufferedImage = {
    val image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setColor(color)
    g.fillRoundRect(2, 2, 28, 28, 10, 10)
    g.setColor(Color.WHITE)
    g.setFont(new Font("DejaVu Sans", Font.BOLD, 17))
    val metrics = g.getFontMetrics
    g.drawString(letter, 16 - metrics.stringWidth(letter) / 2, 22)
    g.dispose()
    image
  }

  /** The screen: the music player window on a plain desktop. */
  val screen: BufferedImage = {
    val image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setColor(new Color(40, 70, 110))
    g.fillRect(0, 0, 1920, 1080)
    val b = player.bounds
    g.translate(b.x, b.y)
    g.setColor(new Color(18, 18, 18))
    g.fillRect(0, 0, b.width, b.height)
    g.setColor(new Color(0, 0, 0))
    g.fillRect(0, 0, 230, b.height)
    g.setFont(new Font("DejaVu Sans", Font.BOLD, 15))
    Seq("Home", "Search", "Library").zipWithIndex.foreach { case (item, i) =>
      g.setColor(if (i == 0) Color.WHITE else new Color(179, 179, 179))
      g.drawString(item, 30, 60 + i * 40)
    }
    Seq(("Music", 260), ("Podcasts", 360), ("Audiobooks", 480)).foreach { case (label, x) =>
      g.setColor(if (label == "Podcasts") new Color(30, 185, 84) else new Color(42, 42, 42))
      g.fillRoundRect(x, 30, if (label == "Audiobooks") 120 else if (label == "Podcasts") 105 else 85, 34, 34, 34)
      g.setColor(if (label == "Podcasts") Color.BLACK else Color.WHITE)
      g.drawString(label, x + 16, 52)
    }
    g.setColor(Color.WHITE)
    g.setFont(new Font("DejaVu Sans", Font.BOLD, 28))
    g.drawString("Discover Weekly", 260, 140)
    g.setStroke(new BasicStroke(1f))
    (0 until 8).foreach { i =>
      g.setColor(new Color(60 + i * 15, 80, 140 - i * 8))
      g.fillRect(260 + (i % 4) * 220, 180 + (i / 4) * 240, 190, 190)
    }
    g.dispose()
    image
  }

  private var cursor = new Point(800, 500)

  def virtualScreen: Rectangle = new Rectangle(0, 0, screen.getWidth, screen.getHeight)

  def cursorPosition: Point = new Point(cursor)

  def moveCursor(x: Int, y: Int): Unit = cursor = new Point(x, y)

  def mousePress(button: MouseButton): Unit = ()

  def mouseRelease(button: MouseButton): Unit = ()

  def keyPress(keyCode: Int): Unit = ()

  def keyRelease(keyCode: Int): Unit = ()

  def scroll(notches: Int): Unit = ()

  def paste(text: String): Unit = ()

  def capture(area: Rectangle): BufferedImage = {
    val image = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.drawImage(screen, -area.x, -area.y, null)
    g.dispose()
    image
  }
}

class DemoWindow(val title: String, area: Rectangle) extends TargetWindow {
  def isOpen: Boolean = true

  def bounds: Rectangle = new Rectangle(area)

  def clientArea: Rectangle = new Rectangle(area)

  def resize(width: Int, height: Int): Unit = ()

  def toFront(): Unit = ()

  def sameWindowAs(other: TargetWindow): Boolean = other eq this
}
