package cv

import org.opencv.core.Rect

case class Match(rect: Rect, confidence: Double, scale: Double = 1.0) {
  def centerX: Int = rect.x + rect.width / 2

  def centerY: Int = rect.y + rect.height / 2
}
