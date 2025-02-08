package util

import java.awt.{MouseInfo, Robot}

object Mouse {
  def moveHumanLike(robot: Robot, endX: Int, endY: Int, speed: Double = 1.0): Unit = {
    val currentMousePos = MouseInfo.getPointerInfo.getLocation

    val (startX, startY) = (currentMousePos.x, currentMousePos.y)
    val distance = Math.sqrt(Math.pow(endX - startX, 2) + Math.pow(endY - startY, 2))
    val steps = ((distance / 10) / speed).toInt.max(10) // Adjust number of steps based on distance and speed

    val baseDelay = (10 / speed).toInt // Base delay between movements in milliseconds, adjusted for speed

    for (i <- 1 to steps) {
      val t = i.toDouble / steps
      val easedT = easeInOutQuad(t) // Apply easing function

      // Calculate intermediate point on a slight curve
      val x = startX + (endX - startX) * easedT + Math.sin(t * Math.PI) * 10
      val y = startY + (endY - startY) * easedT + Math.sin(t * Math.PI) * 5

      robot.mouseMove(x.toInt, y.toInt)

      // Add slight variations to the delay
      val delay = baseDelay + (Math.random() * 5 / speed).toInt
      Thread.sleep(delay)
    }

    // Ensure we end up exactly at the target position
    robot.mouseMove(endX, endY)
  }

  private def easeInOutQuad(t: Double): Double = {
    if (t < 0.5) 2 * t * t else -1 + (4 - 2 * t) * t
  }

}
