package engine

import model.MouseButton

import java.awt.image.BufferedImage
import java.awt.{Point, Rectangle}

/**
 * The window a macro runs in. All coordinates are physical screen pixels, so they stay correct with
 * display scaling (e.g. 150 %) and on monitors with different scaling factors.
 */
trait TargetWindow {
  def title: String

  def isOpen: Boolean

  /** Outer bounds including title bar and borders. */
  def bounds: Rectangle

  /** The content area of the window (without title bar and borders) in screen coordinates. */
  def clientArea: Rectangle

  def resize(width: Int, height: Int): Unit

  def toFront(): Unit

  def sameWindowAs(other: TargetWindow): Boolean
}

case class WindowEntry(window: TargetWindow, icon: Option[BufferedImage])

/** Access to the desktop: windows, mouse, keyboard and screen. Implemented natively for Windows and by fakes in tests. */
trait Desktop {
  def windows(): Seq[WindowEntry]

  /** Bounds of all monitors together in physical pixels. */
  def virtualScreen: Rectangle

  def cursorPosition: Point

  def moveCursor(x: Int, y: Int): Unit

  def mousePress(button: MouseButton): Unit

  def mouseRelease(button: MouseButton): Unit

  def keyPress(keyCode: Int): Unit

  def keyRelease(keyCode: Int): Unit

  /** Scrolls the mouse wheel by the given number of notches (positive = down). */
  def scroll(notches: Int): Unit

  /** Pastes text into the focused application via the clipboard. */
  def paste(text: String): Unit

  /** Screenshot of the given area in physical pixels (the area may span several monitors). */
  def capture(area: Rectangle): BufferedImage
}

/** A stop request shared between the UI, the global ESC hotkey and the macro worker thread. */
class StopSignal {
  @volatile private var requested = false

  def request(): Unit = requested = true

  def reset(): Unit = requested = false

  def isRequested: Boolean = requested
}
