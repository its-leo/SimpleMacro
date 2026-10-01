package engine

import cv.Image.{MultiScaleFactors, agdImageBuffer}
import cv.Match
import model.MacroAction._
import model._

import java.awt.image.BufferedImage
import java.awt.{Rectangle, Point => AwtPoint}

object MacroRunner {
  sealed trait Result

  case object Completed extends Result

  case object Stopped extends Result

  case class Failed(message: String) extends Result

  /** Interval between two screenshots while searching for an image. */
  val SearchIntervalMs = 250
}

/**
 * Executes macros on the calling thread, which must not be the JavaFX application thread.
 *
 * @param onStatus called with a short description of what is currently happening
 */
class MacroRunner(desktop: Desktop, window: TargetWindow, stop: StopSignal, log: MacroLog, onStatus: String => Unit = _ => ()) {

  import MacroRunner._

  private class MacroFailure(message: String) extends Exception(message)

  def run(actions: Seq[MacroAction], loops: Int = 1, label: String = ""): Result = {
    val prefix = if (label.nonEmpty) s"$label - " else ""
    log.info(s"${prefix}Starting macro with ${actions.size} action(s) in '${window.title}'" + (if (loops > 1) s", $loops loops" else ""))

    val result = try {
      var loop = 1
      while (loop <= loops && !stop.isRequested) {
        actions.zipWithIndex.iterator.takeWhile(_ => !stop.isRequested).foreach { case (action, index) =>
          val loopInfo = if (loops > 1) s"Loop $loop/$loops - " else ""
          val description = s"${prefix}${loopInfo}Action ${index + 1}/${actions.size}: ${action.typeName}"
          onStatus(description)
          log.info(s"$description ${action.summary}".trim)
          execute(action)
        }
        if (loop < loops) sleep(500) // Short pause between loops
        loop += 1
      }
      if (stop.isRequested) Stopped else Completed
    } catch {
      case e: MacroFailure => Failed(e.getMessage)
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Stopped
      case e: Exception => Failed(s"Unexpected error: ${e.getMessage}")
    }

    result match {
      case Completed => log.info(s"${prefix}Macro completed")
      case Stopped => log.warn(s"${prefix}Macro stopped")
      case Failed(message) => log.error(s"${prefix}Macro failed: $message")
    }
    result
  }

  // ---------------------------------------------------------------------------

  private def execute(action: MacroAction): Unit = action match {
    case ClickPosition(target, settings) =>
      val origin = prepareWindow(Some(target.windowSize))
      moveMouse(origin.x + target.position.x, origin.y + target.position.y, settings.mouseSpeed)
      click(settings)

    case ClickVisual(image, search, settings) =>
      prepareWindow(None)
      findImage(image, search) match {
        case Some((x, y, m)) =>
          log.info(f"Found image at ($x, $y), confidence ${m.confidence}%.2f" + (if (m.scale != 1.0) f", scale ${m.scale}%.2f" else ""))
          moveMouse(x, y, settings.mouseSpeed)
          click(settings)
        case None if stop.isRequested =>
        case None => throw new MacroFailure(f"The image was not found in the window within ${search.timeoutMs / 1000.0}%.1f seconds.")
      }

    case Wait(seconds) =>
      sleep(seconds * 1000L)

    case TypeText(text) =>
      ensureWindowOpen()
      typeText(text)

    case KeyCombination(keyCode, _, ctrl, alt, shift, meta) =>
      ensureWindowOpen()
      val modifiers = Keyboard.modifierKeys(ctrl, alt, shift, meta)
      modifiers.foreach(desktop.keyPress)
      desktop.keyPress(keyCode)
      sleep(30)
      desktop.keyRelease(keyCode)
      modifiers.reverse.foreach(desktop.keyRelease)
      sleep(50)

    case Scroll(amount, target) =>
      target match {
        case Some(t) =>
          val origin = prepareWindow(Some(t.windowSize))
          moveMouse(origin.x + t.position.x, origin.y + t.position.y, 1.0)
        case None => prepareWindow(None)
      }
      val direction = Integer.signum(amount)
      (1 to Math.abs(amount)).iterator.takeWhile(_ => !stop.isRequested).foreach { _ =>
        desktop.scroll(direction)
        sleep(40)
      }

    case Drag(from, to, windowSize, button, mouseSpeed) =>
      val origin = prepareWindow(Some(windowSize))
      moveMouse(origin.x + from.x, origin.y + from.y, 1.0)
      desktop.mousePress(button)
      try {
        sleep(150) // Many applications only start a drag after the button was held for a moment
        moveMouse(origin.x + to.x, origin.y + to.y, mouseSpeed, ignoreStop = true)
        sleep(150)
      } finally desktop.mouseRelease(button)

    case WaitForImage(image, appear, search) =>
      prepareWindow(None)
      val satisfied = waitUntil(search.timeoutMs) {
        val found = locate(image, search).isDefined
        found == appear
      }
      if (!satisfied && !stop.isRequested) {
        throw new MacroFailure(s"The image did not ${if (appear) "appear" else "disappear"} within ${search.timeoutMs / 1000} seconds.")
      }
  }

  private def ensureWindowOpen(): Unit =
    if (!window.isOpen) throw new MacroFailure(s"The window '${window.title}' was closed.")

  /** Brings the window to the front (restoring the recorded size if given) and returns its top-left corner. */
  private def prepareWindow(size: Option[Size]): AwtPoint = {
    ensureWindowOpen()
    size.foreach { s =>
      val bounds = window.bounds
      if (bounds.width != s.width || bounds.height != s.height) {
        log.info(s"Resizing window from ${bounds.width}x${bounds.height} to ${s.width}x${s.height}")
        window.resize(s.width, s.height)
      }
    }
    window.toFront()
    window.bounds.getLocation
  }

  private def moveMouse(x: Int, y: Int, speed: Double, ignoreStop: Boolean = false): Unit = {
    MousePath.humanLike(desktop.cursorPosition, new AwtPoint(x, y), speed).iterator
      .takeWhile(_ => ignoreStop || !stop.isRequested)
      .foreach { step =>
        desktop.moveCursor(step.x, step.y)
        Thread.sleep(step.delayMs)
      }
    // Ensure we end up exactly at the target position
    desktop.moveCursor(x, y)
  }

  private def click(settings: ClickSettings): Unit = {
    for (i <- 1 to settings.clicks if !stop.isRequested) {
      desktop.mousePress(settings.button)
      desktop.mouseRelease(settings.button)
      if (i < settings.clicks) Thread.sleep(50) // Small delay between clicks for double-click
    }
    sleep(settings.delayAfterMs)
  }

  private def typeText(text: String): Unit =
    Keyboard.segments(text).iterator.takeWhile(_ => !stop.isRequested).foreach {
      case Keyboard.Key(keyCode, shift) =>
        if (shift) desktop.keyPress(java.awt.event.KeyEvent.VK_SHIFT)
        desktop.keyPress(keyCode)
        desktop.keyRelease(keyCode)
        if (shift) desktop.keyRelease(java.awt.event.KeyEvent.VK_SHIFT)
        Thread.sleep(50)
      case Keyboard.Paste(chunk) =>
        desktop.paste(chunk)
        Thread.sleep(50)
    }

  /** Screen position of the image's center, if it is currently visible in the window. */
  private def locate(image: BufferedImage, search: VisualSearch): Option[(Int, Int, Match)] = {
    ensureWindowOpen()
    val area: Rectangle = window.clientArea
    if (area.width <= 0 || area.height <= 0) None
    else {
      val scales = if (search.multiScale) MultiScaleFactors else Seq(1.0)
      desktop.capture(area).findBestMatch(image, search.threshold, scales).map(m => (area.x + m.centerX, area.y + m.centerY, m))
    }
  }

  /** Keeps looking for the image until the timeout; the element might not be visible yet (e.g. a page is still loading). */
  private def findImage(image: BufferedImage, search: VisualSearch): Option[(Int, Int, Match)] = {
    var found: Option[(Int, Int, Match)] = None
    waitUntil(search.timeoutMs) {
      found = locate(image, search)
      found.isDefined
    }
    found
  }

  /** Evaluates the condition (at least once) until it holds, the timeout expires or a stop is requested. */
  private def waitUntil(timeoutMs: Long)(condition: => Boolean): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutMs
    var satisfied = condition
    while (!satisfied && !stop.isRequested && System.currentTimeMillis() < deadline) {
      sleep(SearchIntervalMs.toLong.min(deadline - System.currentTimeMillis()).max(1))
      satisfied = !stop.isRequested && condition
    }
    satisfied
  }

  /** Sleeps in small steps so that a stop request is handled promptly. */
  private def sleep(millis: Long): Unit = {
    val end = System.currentTimeMillis() + millis
    while (!stop.isRequested && System.currentTimeMillis() < end) {
      Thread.sleep(Math.min(50L, end - System.currentTimeMillis()).max(1L))
    }
  }
}
