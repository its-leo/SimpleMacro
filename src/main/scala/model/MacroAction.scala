package model

import java.awt.image.BufferedImage

sealed abstract class MouseButton(val id: String, val label: String)

object MouseButton {
  case object Left extends MouseButton("left", "Left")
  case object Middle extends MouseButton("middle", "Middle")
  case object Right extends MouseButton("right", "Right")

  val all: Seq[MouseButton] = Seq(Left, Middle, Right)

  def fromId(id: String): MouseButton = all.find(_.id == id).getOrElse(throw new IllegalArgumentException(s"Unknown mouse button: $id"))
}

/** A point relative to the top-left corner of the target window, in physical screen pixels. */
case class Point(x: Int, y: Int)

case class Size(width: Int, height: Int)

/** A point in the target window together with the window size it was recorded with. */
case class WindowPoint(position: Point, windowSize: Size)

case class ClickSettings(button: MouseButton = MouseButton.Left, delayAfterMs: Int = 100, clicks: Int = 1, mouseSpeed: Double = 1.0)

/** How a captured image is searched for in the target window. */
case class VisualSearch(threshold: Double = VisualSearch.DefaultThreshold, timeoutMs: Int = 5000, multiScale: Boolean = false)

object VisualSearch {
  val DefaultThreshold = 0.85
}

sealed trait MacroAction {
  def typeName: String

  /** Short description shown next to the type name in the action list. */
  def summary: String
}

object MacroAction {

  case class ClickPosition(target: WindowPoint, settings: ClickSettings = ClickSettings()) extends MacroAction {
    val typeName = "Click Position"

    def summary: String = s"(${target.position.x}, ${target.position.y})${buttonSuffix(settings)}"
  }

  case class ClickVisual(image: BufferedImage, search: VisualSearch = VisualSearch(), settings: ClickSettings = ClickSettings()) extends MacroAction {
    val typeName = "Click Visual"

    def summary: String = buttonSuffix(settings).trim
  }

  case class Wait(seconds: Int) extends MacroAction {
    val typeName = "Wait"

    def summary: String = util.Utils.formatDuration(seconds)
  }

  case class TypeText(text: String) extends MacroAction {
    val typeName = "Type Text"

    def summary: String = "\"" + text.replace("\n", "⏎") + "\""
  }

  /** A key with optional modifiers, e.g. Ctrl+S. `keyCode` is a java.awt.event.KeyEvent VK_ code. */
  case class KeyCombination(keyCode: Int, keyName: String, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, meta: Boolean = false) extends MacroAction {
    val typeName = "Key Combination"

    def summary: String = (Seq(ctrl -> "Ctrl", alt -> "Alt", shift -> "Shift", meta -> "Win").collect { case (true, name) => name } :+ keyName).mkString("+")
  }

  /** Scrolls `amount` notches (positive = down), optionally at a position in the window. */
  case class Scroll(amount: Int, target: Option[WindowPoint] = None) extends MacroAction {
    val typeName = "Scroll"

    def summary: String = {
      val direction = if (amount >= 0) s"down $amount" else s"up ${-amount}"
      target.fold(direction)(t => s"$direction at (${t.position.x}, ${t.position.y})")
    }
  }

  case class Drag(from: Point, to: Point, windowSize: Size, button: MouseButton = MouseButton.Left, mouseSpeed: Double = 0.5) extends MacroAction {
    val typeName = "Drag & Drop"

    def summary: String = s"(${from.x}, ${from.y}) → (${to.x}, ${to.y})"
  }

  /** Waits until the image appears (or disappears) in the window; fails after the search timeout. */
  case class WaitForImage(image: BufferedImage, appear: Boolean = true, search: VisualSearch = VisualSearch(timeoutMs = 30000)) extends MacroAction {
    val typeName = "Wait for Image"

    def summary: String = s"until ${if (appear) "visible" else "gone"} (max. ${util.Utils.formatDuration(search.timeoutMs / 1000)})"
  }

  private def buttonSuffix(settings: ClickSettings): String = {
    val clicks = settings.clicks match {
      case 1 => ""
      case 2 => " double"
      case n => s" ${n}x"
    }
    if (settings.button == MouseButton.Left && clicks.isEmpty) "" else s" ${settings.button.label.toLowerCase}$clicks"
  }
}
