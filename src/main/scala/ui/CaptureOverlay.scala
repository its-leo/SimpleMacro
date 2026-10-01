package ui

import engine.{Desktop, TargetWindow}
import scalafx.Includes._
import scalafx.scene.Scene
import scalafx.scene.canvas.{Canvas, GraphicsContext}
import scalafx.scene.input.{KeyCode, KeyEvent, MouseEvent}
import scalafx.scene.layout.Pane
import scalafx.scene.paint.Color
import scalafx.scene.text.{Font, FontWeight}
import scalafx.stage.{Screen, Stage, StageStyle}

import java.awt.image.BufferedImage
import java.awt.{Point, Rectangle}
import util.Utils.agdBufferedImage

/**
 * Transparent full-screen overlays (one per monitor) to pick positions and areas.
 *
 * Positions are read from the native cursor position, not from JavaFX event coordinates, so the results are
 * physical screen pixels that match the target window even with display scaling and on any monitor.
 * The JavaFX coordinates are only used to draw the feedback.
 */
object CaptureOverlay {

  /** Maximum size of a captured image in physical pixels; larger templates make the search slow. */
  val MaxImageSize = 500

  private sealed trait Mode

  private case object PointMode extends Mode

  private case object LineMode extends Mode

  private case object AreaMode extends Mode

  def pickPoint(desktop: Desktop, window: TargetWindow, hint: String): Option[Point] =
    run(desktop, window, PointMode, hint).map(_._1)

  /** Returns the physical start and end point of a mouse drag. */
  def pickDrag(desktop: Desktop, window: TargetWindow, hint: String): Option[(Point, Point)] =
    run(desktop, window, LineMode, hint).filter { case (a, b) => a != b }

  /** Lets the user select an area and returns its content as it looked before the overlay was shown. */
  def pickImage(desktop: Desktop, window: TargetWindow, hint: String): Option[BufferedImage] = {
    window.toFront()
    val screen = desktop.virtualScreen
    // Screenshot before the overlay is shown, so the image contains neither the overlay tint nor the selection
    val snapshot = desktop.capture(screen)
    run(desktop, window, AreaMode, hint).flatMap { case (start, end) =>
      val area = limitedArea(start, end).intersection(screen)
      if (area.width < 4 || area.height < 4) None
      else Some(snapshot.crop(area.x - screen.x, area.y - screen.y, area.width, area.height))
    }
  }

  /** The selected rectangle, limited to MaxImageSize in each direction from the start point. */
  def limitedArea(start: Point, end: Point): Rectangle = {
    def clamp(from: Int, to: Int) = from + (to - from).max(-MaxImageSize).min(MaxImageSize)

    val endX = clamp(start.x, end.x)
    val endY = clamp(start.y, end.y)
    new Rectangle(start.x.min(endX), start.y.min(endY), (endX - start.x).abs, (endY - start.y).abs)
  }

  // ---------------------------------------------------------------------------

  private def run(desktop: Desktop, window: TargetWindow, mode: Mode, hint: String): Option[(Point, Point)] = {
    window.toFront()

    var result: Option[(Point, Point)] = None
    var pressed: Option[(Point, Double, Double)] = None // physical point and local coordinates at press

    lazy val stages: Seq[Stage] = Screen.screens.toSeq.map { screen =>
      val bounds = screen.bounds
      val canvas = new Canvas(bounds.width, bounds.height)
      val gc = canvas.graphicsContext2D
      val outputScale = screen.delegate.getOutputScaleX

      def redraw(mouseX: Double, mouseY: Double, dragging: Boolean): Unit = {
        gc.clearRect(0, 0, canvas.width.value, canvas.height.value)
        drawHint(gc, canvas.width.value, hint)

        val cursor = desktop.cursorPosition
        val windowBounds = window.bounds
        val relX = cursor.x - windowBounds.x
        val relY = cursor.y - windowBounds.y
        val insideWindow = windowBounds.contains(cursor)

        if (dragging) pressed.foreach { case (_, startX, startY) =>
          gc.stroke = Color.Red
          gc.lineWidth = 2
          mode match {
            case LineMode =>
              gc.strokeLine(startX, startY, mouseX, mouseY)
              gc.fill = Color.Red
              gc.fillOval(startX - 5, startY - 5, 10, 10)
            case _ =>
              // Same limit as limitedArea, converted to this screen's JavaFX coordinates
              val max = MaxImageSize / outputScale
              val endX = startX + (mouseX - startX).max(-max).min(max)
              val endY = startY + (mouseY - startY).max(-max).min(max)
              gc.strokeRect(startX.min(endX), startY.min(endY), (endX - startX).abs, (endY - startY).abs)
          }
        }

        gc.fill = if (insideWindow) Color.Red else Color.Gray
        gc.fillOval(mouseX - 5, mouseY - 5, 10, 10)
        gc.font = Font.font(13)
        val label = if (insideWindow) s"($relX, $relY)" else "outside of the window"
        gc.fill = Color.rgb(0, 0, 0, 0.7)
        gc.fillRoundRect(mouseX + 10, mouseY - 22, label.length * 7.5 + 12, 22, 8, 8)
        gc.fill = Color.White
        gc.fillText(label, mouseX + 16, mouseY - 6)
      }

      new Stage(StageStyle.Transparent) {
        title = "Capture"
        alwaysOnTop = true
        x = bounds.minX
        y = bounds.minY
        width = bounds.width
        height = bounds.height
        scene = new Scene(bounds.width, bounds.height) {
          fill = Color.Transparent
          root = new Pane {
            // Not fully transparent: fully transparent pixels do not receive mouse events on Windows
            style = "-fx-background-color: rgba(0, 0, 0, 0.12);"
            children = canvas
          }
          cursor = scalafx.scene.Cursor.Crosshair
          onKeyPressed = (e: KeyEvent) => if (e.code == KeyCode.Escape) closeAll()
        }
        canvas.onMouseMoved = (e: MouseEvent) => redraw(e.x, e.y, dragging = false)
        canvas.onMousePressed = (e: MouseEvent) => pressed = Some((desktop.cursorPosition, e.x, e.y))
        canvas.onMouseDragged = (e: MouseEvent) => if (mode != PointMode) redraw(e.x, e.y, dragging = true)
        // Finish on release, so the release does not reach the application below the overlay
        canvas.onMouseReleased = (_: MouseEvent) => {
          result = pressed.map(p => (p._1, if (mode == PointMode) p._1 else desktop.cursorPosition))
          closeAll()
        }
        onShown = _ => redraw(-100, -100, dragging = false)
      }
    }

    def closeAll(): Unit = stages.foreach(_.close())

    val primaryBounds = Screen.primary.bounds
    val primary = stages.find(s => s.x.value == primaryBounds.minX && s.y.value == primaryBounds.minY).getOrElse(stages.head)
    val others = stages.filterNot(_ eq primary)
    others.foreach(_.show())
    primary.showAndWait()
    closeAll()
    result
  }

  private def drawHint(gc: GraphicsContext, width: Double, text: String): Unit = {
    val fullText = s"$text   (ESC = cancel)"
    val boxWidth = fullText.length * 8.0 + 30
    gc.fill = Color.rgb(0, 0, 0, 0.75)
    gc.fillRoundRect((width - boxWidth) / 2, 20, boxWidth, 36, 12, 12)
    gc.fill = Color.White
    gc.font = Font.font(null, FontWeight.Bold, 14)
    gc.fillText(fullText, (width - boxWidth) / 2 + 15, 43)
  }
}
