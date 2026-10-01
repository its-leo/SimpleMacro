package util

import org.scalatest.funsuite.AnyFunSuite
import util.Utils.agdBufferedImage

import java.awt.Color
import java.awt.image.BufferedImage

class UtilsSpec extends AnyFunSuite {

  test("formatDuration") {
    assert(Utils.formatDuration(0) == "0 seconds")
    assert(Utils.formatDuration(1) == "1 second")
    assert(Utils.formatDuration(90) == "1 minute 30 seconds")
    assert(Utils.formatDuration(7200) == "2 hours")
    assert(Utils.formatDuration(3725) == "1 hour 2 minutes 5 seconds")
  }

  test("crop returns an independent copy of the region") {
    val source = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
    source.setRGB(3, 4, Color.RED.getRGB)

    val cropped = source.crop(3, 4, 2, 2)
    assert(cropped.getWidth == 2 && cropped.getHeight == 2)
    assert((cropped.getRGB(0, 0) & 0xFFFFFF) == (Color.RED.getRGB & 0xFFFFFF))

    source.setRGB(3, 4, Color.BLUE.getRGB)
    assert((cropped.getRGB(0, 0) & 0xFFFFFF) == (Color.RED.getRGB & 0xFFFFFF))
  }
}
