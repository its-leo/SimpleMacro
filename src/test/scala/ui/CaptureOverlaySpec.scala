package ui

import org.scalatest.funsuite.AnyFunSuite

import java.awt.{Point, Rectangle}

class CaptureOverlaySpec extends AnyFunSuite {
  test("selected areas are normalized and limited from the start point") {
    assert(CaptureOverlay.limitedArea(new Point(100, 100), new Point(50, 80)) == new Rectangle(50, 80, 50, 20))
    assert(CaptureOverlay.limitedArea(new Point(0, 0), new Point(2000, 10)) == new Rectangle(0, 0, CaptureOverlay.MaxImageSize, 10))
    assert(CaptureOverlay.limitedArea(new Point(1000, 1000), new Point(0, 990)) ==
      new Rectangle(1000 - CaptureOverlay.MaxImageSize, 990, CaptureOverlay.MaxImageSize, 10))
  }
}
