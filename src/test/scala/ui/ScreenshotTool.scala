package ui

import javafx.animation.PauseTransition
import javafx.embed.swing.SwingFXUtils
import model.MacroAction._
import model._
import scalafx.Includes._
import scalafx.application.JFXApp3
import scalafx.application.JFXApp3.PrimaryStage
import scalafx.stage.Stage
import util.Utils.agdBufferedImage

import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.{BasicStroke, Color, Font, RenderingHints}
import java.io.File
import javax.imageio.ImageIO

/**
 * Creates the README screenshots with the real UI and a simulated desktop.
 *
 * Run on Linux with: xvfb-run -s "-screen 0 1920x1080x24" sbt "Test/runMain ui.ScreenshotTool"
 */
object ScreenshotTool extends JFXApp3 {

  private val outputDir = new File(sys.props.getOrElse("screenshots.dir", "src/main/resources/screenshots"))
  private val FontStyle = "-fx-font-family: 'Liberation Sans';"

  override def main(args: Array[String]): Unit = {
    System.setProperty("prism.order", "sw")
    System.setProperty("prism.lcdtext", "false")
    super.main(args)
  }

  override def start(): Unit = {
    outputDir.mkdirs()
    stage = new PrimaryStage {
      title = "SimpleMacro"
      icons += UiKit.appIcon
    }

    val desktop = new DemoDesktop
    val state = new AppState(desktop)
    val main = new MainWindow(stage, state)
    val window = desktop.player
    val origin = window.bounds

    def windowPoint(x: Int, y: Int) = WindowPoint(Point(x - origin.x, y - origin.y), Size(origin.width, origin.height))

    val podcasts = desktop.screen.crop(origin.x + 352, origin.y + 24, 121, 46)

    val sampleActions = Seq(
      ClickVisual(podcasts, VisualSearch(timeoutMs = 5000, multiScale = true)),
      ClickPosition(windowPoint(origin.x + 60, origin.y + 100)),
      Wait(1),
      TypeText("Linkin Park"),
      KeyCombination(KeyEvent.VK_ENTER, "Enter"),
      WaitForImage(podcasts, appear = true, VisualSearch(timeoutMs = 10000)),
      Scroll(5, Some(windowPoint(origin.x + 600, origin.y + 500))),
      Drag(Point(400, 300), Point(700, 300), Size(origin.width, origin.height))
    )

    def styled(s: Stage): Unit = s.scene().getRoot.setStyle(FontStyle)

    def lookupList(s: Stage) = s.scene().lookup(".list-view").asInstanceOf[javafx.scene.control.ListView[_]]

    var dialog: Option[Stage] = None

    val steps: List[(() => Stage, String)] = List(
      (() => {
        main.showStep(1)
        styled(stage)
        lookupList(stage).getSelectionModel.select(0)
        stage
      }, "main_window_screenshot.png"),
      (() => {
        state.selectedWindow = Some(window)
        state.actions.setAll(sampleActions: _*)
        main.showStep(2)
        styled(stage)
        lookupList(stage).getSelectionModel.select(1)
        stage
      }, "action_config_screenshot.png"),
      (() => {
        val d = new ActionDialog(state, stage, None)(_ => ())
        dialog = Some(d.stage)
        d.stage.show()
        styled(d.stage)
        d.stage
      }, "add_action_pane_screenshot.png"),
      (() => {
        dialog.foreach(_.close())
        val d = new ActionDialog(state, stage, Some(sampleActions.head))(_ => ())
        dialog = Some(d.stage)
        d.stage.show()
        styled(d.stage)
        d.stage
      }, "edit_action_screenshot.png"),
      (() => {
        dialog.foreach(_.close())
        main.showStep(3)
        styled(stage)
        stage.scene().getRoot.lookupAll(".button").forEach {
          case b: javafx.scene.control.Button if b.getText == "Execute" => b.fire()
          case _ =>
        }
        stage
      }, "macro_execution_screenshot.png")
    )

    def run(remaining: List[(() => Stage, String)]): Unit = remaining match {
      case Nil => scalafx.application.Platform.exit()
      case (prepare, fileName) :: rest =>
        val target = prepare()
        // The last step waits for the macro execution to finish
        val pause = new PauseTransition(javafx.util.Duration.millis(if (rest.isEmpty) 9000 else 1200))
        pause.setOnFinished(_ => {
          val snapshot = SwingFXUtils.fromFXImage(target.scene().snapshot(null), null)
          ImageIO.write(withFrame(snapshot, target.title.value), "png", new File(outputDir, fileName))
          println(s"Saved $fileName")
          run(rest)
        })
        pause.play()
    }

    run(steps)
  }

  /** Draws a simple window frame with title bar around the scene snapshot. */
  private def withFrame(content: BufferedImage, title: String): BufferedImage = {
    val titleHeight = 32
    val result = new BufferedImage(content.getWidth + 2, content.getHeight + titleHeight + 1, BufferedImage.TYPE_INT_RGB)
    val g = result.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setColor(new Color(160, 160, 160))
    g.fillRect(0, 0, result.getWidth, result.getHeight)
    g.setColor(new Color(238, 241, 245))
    g.fillRect(1, 1, result.getWidth - 2, titleHeight)
    val icon = ImageIO.read(getClass.getResourceAsStream("/icons/robot_icon.png"))
    g.drawImage(icon, 10, 8, 18, 18, null)
    g.setColor(new Color(30, 30, 30))
    g.setFont(new Font("Liberation Sans", Font.PLAIN, 13))
    g.drawString(title, 36, 21)
    // Minimize, maximize and close symbols
    val right = result.getWidth - 1
    g.setStroke(new BasicStroke(1f))
    g.drawLine(right - 128, 17, right - 118, 17)
    g.drawRect(right - 82, 12, 9, 9)
    g.drawLine(right - 34, 12, right - 25, 21)
    g.drawLine(right - 34, 21, right - 25, 12)
    g.drawImage(content, 1, titleHeight + 1, null)
    g.dispose()
    result
  }
}
