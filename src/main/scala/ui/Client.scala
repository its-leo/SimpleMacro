package ui

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.{NativeKeyEvent, NativeKeyListener}
import scalafx.application.JFXApp3.PrimaryStage
import scalafx.application.{JFXApp3, Platform}
import scalafx.stage.Stage
import win.Win32Desktop

import java.util.logging.{Level, Logger}
import scala.util.Try

/** Navigation between the three steps. */
class MainWindow(stage: Stage, state: AppState) {

  private var dispose: () => Unit = () => ()

  def showStep(step: Int): Unit = {
    dispose()
    step match {
      case 1 =>
        val view = new WindowSelectionView(state, () => showStep(2))
        dispose = () => view.dispose()
        stage.scene = view.scene
      case 2 =>
        val view = new ActionListView(state, stage, () => showStep(1), () => showStep(3))
        dispose = () => ()
        stage.scene = view.scene
      case 3 =>
        val view = new ExecutionView(state, stage, () => showStep(2))
        dispose = () => view.dispose()
        stage.scene = view.scene
      case _ => throw new IllegalArgumentException(s"Invalid step number: $step")
    }
  }
}

object Client extends JFXApp3 {

  /** Our own windows are hidden from the window list. */
  val OwnWindowTitles = Set("SimpleMacro", "Add Action", "Edit Action", "Capture")

  private var state: Option[AppState] = None
  private var autosave: Option[Autosave] = None

  override def main(args: Array[String]): Unit = {
    // Must happen before the first window is created
    Win32Desktop.enablePerMonitorDpiAwareness()
    super.main(args)
  }

  override def start(): Unit = {
    stage = new PrimaryStage {
      title = "SimpleMacro"
      width = 760
      height = 640
      minWidth = 600
      minHeight = 500
      icons += UiKit.appIcon
    }

    val appState = new AppState(new Win32Desktop(OwnWindowTitles))
    state = Some(appState)

    val save = new Autosave(appState)
    save.restore()
    save.start()
    autosave = Some(save)

    registerStopHotkey(appState)
    new MainWindow(stage, appState).showStep(1)
  }

  override def stopApp(): Unit = {
    autosave.foreach(_.saveNow())
    state.foreach { s =>
      s.stop.request()
      s.scheduler.cancel()
    }
    // The native hook thread would otherwise keep the JVM alive after the window is closed
    Try(GlobalScreen.unregisterNativeHook())
  }

  /** ESC stops the macro even while another application has the focus. */
  private def registerStopHotkey(appState: AppState): Unit = {
    Logger.getLogger(classOf[GlobalScreen].getPackage.getName).setLevel(Level.OFF)
    Try {
      GlobalScreen.registerNativeHook()
      GlobalScreen.addNativeKeyListener(new NativeKeyListener {
        override def nativeKeyPressed(e: NativeKeyEvent): Unit =
          if (e.getKeyCode == NativeKeyEvent.VC_ESCAPE) {
            // Set directly: the JavaFX thread may be busy, so runLater could delay the stop
            appState.stop.request()
            Platform.runLater(if (appState.busy.value) appState.requestStop())
          }
      })
    }.failed.foreach(e => appState.log.warn(s"Global ESC hotkey unavailable: ${e.getMessage}"))
  }
}
