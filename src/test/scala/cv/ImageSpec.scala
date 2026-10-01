package cv

import cv.Image.agdImageBuffer
import org.scalatest.funsuite.AnyFunSuite
import util.Utils.agdBufferedImage

import java.awt.Color
import java.awt.image.BufferedImage
import scala.util.Random

class ImageSpec extends AnyFunSuite {

  private def noiseImage(width: Int, height: Int, seed: Long): BufferedImage = {
    val random = new Random(seed)
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (x <- 0 until width; y <- 0 until height) image.setRGB(x, y, new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)).getRGB)
    image
  }

  test("finds a template at its exact position") {
    val haystack = noiseImage(200, 150, seed = 1)
    val needle = haystack.crop(120, 40, 30, 20)

    val result = haystack.findBestMatch(needle)
    assert(result.isDefined)
    assert(result.get.rect.x == 120 && result.get.rect.y == 40)
    assert(result.get.rect.width == 30 && result.get.rect.height == 20)
    assert(result.get.confidence > 0.99)
  }

  test("returns None when the template is not present") {
    val haystack = noiseImage(200, 150, seed = 1)
    val needle = noiseImage(30, 20, seed = 2)
    assert(haystack.findBestMatch(needle).isEmpty)
  }

  test("returns None instead of failing when the template is larger than the image") {
    val haystack = noiseImage(20, 20, seed = 1)
    val needle = noiseImage(30, 20, seed = 2)
    assert(haystack.findBestMatch(needle).isEmpty)
  }
}
