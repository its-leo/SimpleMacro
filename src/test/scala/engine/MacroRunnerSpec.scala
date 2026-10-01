package engine

import engine.MacroRunner.{Completed, Failed, Stopped}
import model.MacroAction._
import model._
import org.scalatest.funsuite.AnyFunSuite
import util.Utils.agdBufferedImage

import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.{Color, Rectangle}
import scala.util.Random

class MacroRunnerSpec extends AnyFunSuite {

  private def noise(width: Int, height: Int, seed: Long): BufferedImage = {
    val random = new Random(seed)
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (x <- 0 until width; y <- 0 until height) image.setRGB(x, y, new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)).getRGB)
    image
  }

  private def runner(desktop: Desktop, window: TargetWindow, stop: StopSignal = new StopSignal, log: MacroLog = new MacroLog()) =
    new MacroRunner(desktop, window, stop, log)

  private val fastClick = ClickSettings(delayAfterMs = 0, mouseSpeed = 5.0)

  test("click position clicks relative to the window and restores the recorded window size") {
    val desktop = new FakeDesktop()
    val window = new FakeWindow(new Rectangle(100, 50, 400, 300))
    val action = ClickPosition(WindowPoint(Point(10, 20), Size(500, 300)), fastClick.copy(button = MouseButton.Right, clicks = 2))

    assert(runner(desktop, window).run(Seq(action)) == Completed)
    assert(window.resizes == Seq((500, 300)))
    assert(desktop.events == Seq("press right at 110,70", "release right at 110,70", "press right at 110,70", "release right at 110,70"))
  }

  test("click visual clicks the center of the found image") {
    val screen = noise(400, 300, seed = 1)
    val desktop = new FakeDesktop(screen)
    val window = new FakeWindow(new Rectangle(50, 40, 300, 200))
    val template = screen.crop(150, 100, 30, 20) // at (100, 60) inside the window

    assert(runner(desktop, window).run(Seq(ClickVisual(template, settings = fastClick))) == Completed)
    assert(desktop.events == Seq("press left at 165,110", "release left at 165,110"))
  }

  test("click visual fails when the image is not found within the timeout") {
    val desktop = new FakeDesktop(noise(400, 300, seed = 1))
    val window = new FakeWindow(new Rectangle(0, 0, 400, 300))
    val result = runner(desktop, window).run(Seq(ClickVisual(noise(30, 20, seed = 2), VisualSearch(timeoutMs = 300), fastClick)))
    assert(result.isInstanceOf[Failed])
    assert(desktop.events.isEmpty)
  }

  /** A UI-like scene: a few colored buttons with labels, drawn at the given zoom factor. */
  private def renderUi(width: Int, height: Int, zoom: Double): BufferedImage = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setColor(Color.WHITE)
    g.fillRect(0, 0, width, height)
    g.scale(zoom, zoom)
    Seq(("Cancel", new Color(220, 60, 50), 20), ("Save", new Color(70, 130, 180), 140), ("Login", new Color(76, 175, 80), 260)).foreach {
      case (label, color, x) =>
        g.setColor(color)
        g.fillRoundRect(x, 40, 100, 36, 12, 12)
        g.setColor(Color.WHITE)
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 16))
        g.drawString(label, x + 22, 64)
        g.fillOval(x + 80, 52, 10, 10)
    }
    g.dispose()
    image
  }

  test("multi-scale search finds an element captured at a different zoom") {
    val screen = renderUi(420, 120, zoom = 1.0)
    val window = new FakeWindow(new Rectangle(0, 0, 420, 120))
    // The template was captured while the application was zoomed to 80 %
    val template = renderUi(420, 120, zoom = 0.8).crop(208, 32, 80, 29) // the "Login" button

    val withoutScaling = runner(new FakeDesktop(screen), window).run(Seq(ClickVisual(template, VisualSearch(timeoutMs = 100), fastClick)))
    assert(withoutScaling.isInstanceOf[Failed])

    val desktop = new FakeDesktop(screen)
    val result = runner(desktop, window).run(Seq(ClickVisual(template, VisualSearch(timeoutMs = 100, multiScale = true), fastClick)))
    assert(result == Completed)
    // The Login button is at x = 260..360, y = 40..76 at 100 %
    val Array(x, y) = desktop.events.head.stripPrefix("press left at ").split(',').map(_.toInt)
    assert(x > 290 && x < 330 && y > 45 && y < 72, desktop.events.head)
  }

  test("multi-scale search does not find elements that are not there") {
    val screen = renderUi(420, 120, zoom = 1.0)
    val window = new FakeWindow(new Rectangle(0, 0, 420, 120))
    val other = new BufferedImage(80, 30, BufferedImage.TYPE_INT_RGB)
    val g = other.createGraphics()
    g.setColor(Color.WHITE)
    g.fillRect(0, 0, 80, 30)
    g.setColor(new Color(156, 39, 176))
    g.fillOval(5, 3, 24, 24)
    g.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.ITALIC, 14))
    g.drawString("Help", 36, 20)
    g.dispose()
    val result = runner(new FakeDesktop(screen), window).run(Seq(ClickVisual(other, VisualSearch(timeoutMs = 100, multiScale = true), fastClick)))
    assert(result.isInstanceOf[Failed])
  }

  test("wait for image disappearing succeeds once the image is gone") {
    val screen = noise(200, 200, seed = 4)
    val desktop = new FakeDesktop(screen)
    val window = new FakeWindow(new Rectangle(0, 0, 200, 200))
    val template = screen.crop(50, 50, 30, 30)

    val remover = new Thread(() => {
      Thread.sleep(300)
      desktop.synchronized(desktop.screen = noise(200, 200, seed = 5))
    })
    remover.start()
    assert(runner(desktop, window).run(Seq(WaitForImage(template, appear = false, VisualSearch(timeoutMs = 3000)))) == Completed)
  }

  test("wait for image fails after the timeout") {
    val desktop = new FakeDesktop(noise(200, 200, seed = 4))
    val window = new FakeWindow(new Rectangle(0, 0, 200, 200))
    val result = runner(desktop, window).run(Seq(WaitForImage(noise(30, 30, seed = 9), appear = true, VisualSearch(timeoutMs = 200))))
    assert(result.isInstanceOf[Failed])
  }

  test("key combinations press modifiers first and release them last") {
    val desktop = new FakeDesktop()
    val action = KeyCombination(KeyEvent.VK_S, "S", ctrl = true, shift = true)
    assert(runner(desktop, new FakeWindow(new Rectangle(0, 0, 10, 10))).run(Seq(action)) == Completed)
    assert(desktop.events == Seq(
      s"key down ${KeyEvent.VK_CONTROL}", s"key down ${KeyEvent.VK_SHIFT}", s"key down ${KeyEvent.VK_S}",
      s"key up ${KeyEvent.VK_S}", s"key up ${KeyEvent.VK_SHIFT}", s"key up ${KeyEvent.VK_CONTROL}"))
  }

  test("type text types keys and pastes other characters") {
    val desktop = new FakeDesktop()
    assert(runner(desktop, new FakeWindow(new Rectangle(0, 0, 10, 10))).run(Seq(TypeText("a€"))) == Completed)
    assert(desktop.events == Seq(s"key down ${KeyEvent.VK_A}", s"key up ${KeyEvent.VK_A}", "paste €"))
  }

  test("scroll moves to the position and scrolls notch by notch") {
    val desktop = new FakeDesktop()
    val window = new FakeWindow(new Rectangle(10, 10, 100, 100))
    assert(runner(desktop, window).run(Seq(Scroll(-3, Some(WindowPoint(Point(5, 5), Size(100, 100)))))) == Completed)
    assert(desktop.cursorPosition == new java.awt.Point(15, 15))
    assert(desktop.events == Seq("scroll -1", "scroll -1", "scroll -1"))
  }

  test("drag presses at the start and releases at the target") {
    val desktop = new FakeDesktop()
    val window = new FakeWindow(new Rectangle(0, 0, 300, 300))
    assert(runner(desktop, window).run(Seq(Drag(Point(10, 10), Point(200, 150), Size(300, 300), MouseButton.Left, 5.0))) == Completed)
    assert(desktop.events == Seq("press left at 10,10", "release left at 200,150"))
  }

  test("a closed window fails the macro") {
    val window = new FakeWindow(new Rectangle(0, 0, 100, 100))
    window.open = false
    val result = runner(new FakeDesktop(), window).run(Seq(ClickPosition(WindowPoint(Point(1, 1), Size(100, 100)), fastClick)))
    assert(result.isInstanceOf[Failed])
  }

  test("a stop request ends a long wait promptly") {
    val stop = new StopSignal
    val start = System.currentTimeMillis()
    new Thread(() => {
      Thread.sleep(200)
      stop.request()
    }).start()
    val result = runner(new FakeDesktop(), new FakeWindow(new Rectangle(0, 0, 10, 10)), stop).run(Seq(Wait(60), TypeText("x")), loops = 3)
    assert(result == Stopped)
    assert(System.currentTimeMillis() - start < 2000)
  }

  test("loops repeat all actions and everything is logged") {
    val desktop = new FakeDesktop()
    val log = new MacroLog()
    assert(runner(desktop, new FakeWindow(new Rectangle(0, 0, 10, 10)), log = log).run(Seq(TypeText("a"), TypeText("b")), loops = 2) == Completed)
    assert(desktop.events.count(_ == s"key down ${KeyEvent.VK_A}") == 2)
    val messages = log.entries.map(_.message)
    assert(messages.exists(_.contains("Loop 2/2 - Action 2/2: Type Text")))
    assert(messages.last == "Macro completed")
  }
}
