package util

import org.scalatest.funsuite.AnyFunSuite
import util.Keyboard.{Key, Paste}

import java.awt.event.KeyEvent

class KeyboardSpec extends AnyFunSuite {

  test("lowercase letters are typed without shift") {
    assert(Keyboard.keyFor('a').contains(Key(KeyEvent.VK_A, shift = false)))
    assert(Keyboard.keyFor('z').contains(Key(KeyEvent.VK_Z, shift = false)))
  }

  test("uppercase letters are typed with shift") {
    assert(Keyboard.keyFor('Q').contains(Key(KeyEvent.VK_Q, shift = true)))
  }

  test("digits and whitespace map to their keys") {
    assert(Keyboard.keyFor('7').contains(Key(KeyEvent.VK_7, shift = false)))
    assert(Keyboard.keyFor(' ').contains(Key(KeyEvent.VK_SPACE, shift = false)))
    assert(Keyboard.keyFor('\n').contains(Key(KeyEvent.VK_ENTER, shift = false)))
  }

  test("layout dependent characters have no key") {
    Seq('@', 'ä', '!', '€', '.').foreach(c => assert(Keyboard.keyFor(c).isEmpty, c))
  }

  test("consecutive non-typeable characters are pasted together") {
    assert(Keyboard.segments("Hi, @ä!") == List(
      Key(KeyEvent.VK_H, shift = true),
      Key(KeyEvent.VK_I, shift = false),
      Paste(","),
      Key(KeyEvent.VK_SPACE, shift = false),
      Paste("@ä!")
    ))
  }

  test("windows line endings produce a single enter") {
    assert(Keyboard.segments("a\r\nb") == List(
      Key(KeyEvent.VK_A, shift = false),
      Key(KeyEvent.VK_ENTER, shift = false),
      Key(KeyEvent.VK_B, shift = false)
    ))
  }

  test("empty text produces no segments") {
    assert(Keyboard.segments("").isEmpty)
  }
}
