package util

import java.awt.datatransfer.{Clipboard, DataFlavor, StringSelection, Transferable}
import java.awt.event.KeyEvent
import java.awt.{Robot, Toolkit}
import scala.util.Try

object Keyboard {

  sealed trait Segment

  /** A single key stroke, optionally with SHIFT held down. */
  case class Key(keyCode: Int, shift: Boolean) extends Segment

  /** Text that cannot be typed layout-independently and is pasted via the clipboard instead. */
  case class Paste(text: String) extends Segment

  /**
   * Key code for characters whose virtual key is the same on every common keyboard layout
   * (letters, digits, whitespace). Everything else (symbols, umlauts, emoji, ...) depends on the
   * active layout and returns None.
   */
  def keyFor(c: Char): Option[Key] = c match {
    case _ if c >= 'a' && c <= 'z' => Some(Key(KeyEvent.VK_A + (c - 'a'), shift = false))
    case _ if c >= 'A' && c <= 'Z' => Some(Key(KeyEvent.VK_A + (c - 'A'), shift = true))
    case _ if c >= '0' && c <= '9' => Some(Key(KeyEvent.VK_0 + (c - '0'), shift = false))
    case ' ' => Some(Key(KeyEvent.VK_SPACE, shift = false))
    case '\n' => Some(Key(KeyEvent.VK_ENTER, shift = false))
    case '\t' => Some(Key(KeyEvent.VK_TAB, shift = false))
    case _ => None
  }

  /** Splits text into key strokes and clipboard pastes, merging consecutive non-typeable characters. */
  def segments(text: String): List[Segment] =
    text.replace("\r\n", "\n").foldLeft(List.empty[Segment]) { (acc, c) =>
      (keyFor(c), acc) match {
        case (Some(key), _) => key :: acc
        case (None, Paste(prev) :: rest) => Paste(prev + c) :: rest
        case (None, _) => Paste(c.toString) :: acc
      }
    }.reverse

  def typeText(robot: Robot, text: String, shouldStop: () => Boolean, delayMs: Int = 50): Unit =
    segments(text).iterator.takeWhile(_ => !shouldStop()).foreach {
      case Key(keyCode, shift) =>
        if (shift) robot.keyPress(KeyEvent.VK_SHIFT)
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        if (shift) robot.keyRelease(KeyEvent.VK_SHIFT)
        robot.delay(delayMs)
      case Paste(chunk) =>
        paste(robot, chunk)
        robot.delay(delayMs)
    }

  private def paste(robot: Robot, text: String): Unit = {
    val clipboard: Clipboard = Toolkit.getDefaultToolkit.getSystemClipboard
    val previous: Option[Transferable] = Try(clipboard.getContents(null)).toOption.flatMap(Option(_))

    clipboard.setContents(new StringSelection(text), null)
    robot.keyPress(KeyEvent.VK_CONTROL)
    robot.keyPress(KeyEvent.VK_V)
    robot.keyRelease(KeyEvent.VK_V)
    robot.keyRelease(KeyEvent.VK_CONTROL)
    // Give the target application time to read the clipboard before restoring it
    robot.delay(150)

    previous.filter(_.isDataFlavorSupported(DataFlavor.stringFlavor)).foreach { p =>
      Try(clipboard.setContents(p, null))
    }
  }
}
