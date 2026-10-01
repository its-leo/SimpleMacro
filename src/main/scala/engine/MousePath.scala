package engine

import java.awt.Point

object MousePath {

  case class Step(x: Int, y: Int, delayMs: Int)

  /**
   * Points of a slightly curved, eased mouse movement from start to end. The last step is always
   * exactly the end point. `random` provides small variations of the delay between steps.
   */
  def humanLike(start: Point, end: Point, speed: Double = 1.0, random: () => Double = () => Math.random()): Seq[Step] = {
    val safeSpeed = speed.max(0.05)
    val distance = start.distance(end)
    // Adjust number of steps based on distance and speed
    val steps = ((distance / 10) / safeSpeed).toInt.max(10)
    val baseDelay = (10 / safeSpeed).toInt

    // Curve strength depends on the distance, so short movements stay precise
    val curve = (distance / 30).min(10)

    (1 to steps).map { i =>
      val t = i.toDouble / steps
      val easedT = easeInOutQuad(t)
      val x = start.x + (end.x - start.x) * easedT + Math.sin(t * Math.PI) * curve
      val y = start.y + (end.y - start.y) * easedT + Math.sin(t * Math.PI) * curve / 2
      val delay = baseDelay + (random() * 5 / safeSpeed).toInt
      if (i == steps) Step(end.x, end.y, delay) else Step(Math.round(x).toInt, Math.round(y).toInt, delay)
    }
  }

  private def easeInOutQuad(t: Double): Double =
    if (t < 0.5) 2 * t * t else -1 + (4 - 2 * t) * t
}
