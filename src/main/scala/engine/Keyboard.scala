package engine

import java.awt.event.KeyEvent

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

  /** Modifier key codes in the order they are pressed (released in reverse order). */
  def modifierKeys(ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): Seq[Int] =
    Seq(ctrl -> KeyEvent.VK_CONTROL, alt -> KeyEvent.VK_ALT, shift -> KeyEvent.VK_SHIFT, meta -> KeyEvent.VK_WINDOWS)
      .collect { case (true, code) => code }

  def isModifier(keyCode: Int): Boolean =
    Set(KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_SHIFT, KeyEvent.VK_WINDOWS, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH).contains(keyCode)
}
