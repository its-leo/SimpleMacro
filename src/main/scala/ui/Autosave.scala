package ui

import javafx.animation.PauseTransition
import model.{MacroAction, MacroFile}
import scalafx.collections.ObservableBuffer

import java.util.concurrent.Executors
import scala.util.{Failure, Success, Try}

/** Saves the actions shortly after every change, so they are restored on the next start. */
class Autosave(state: AppState) {

  private val file = MacroFile.autosaveFile
  private val writer = Executors.newSingleThreadExecutor((r: Runnable) => {
    val thread = new Thread(r, "autosave")
    thread.setDaemon(true)
    thread
  })

  private val debounce = new PauseTransition(javafx.util.Duration.seconds(1))
  debounce.setOnFinished(_ => saveInBackground())

  def restore(): Unit = if (file.isFile) {
    Try(MacroFile.load(file)) match {
      case Success(actions) =>
        state.actions.setAll(actions: _*)
        if (actions.nonEmpty) state.log.info(s"Restored ${actions.size} action(s) from the last session")
      case Failure(e) => state.log.warn(s"Could not restore the last session: ${e.getMessage}")
    }
  }

  def start(): Unit = state.actions.onChange((_: ObservableBuffer[MacroAction], _) => debounce.playFromStart())

  /** Saves synchronously, e.g. when the application exits. */
  def saveNow(): Unit = {
    debounce.stop()
    save(state.actions.toList)
  }

  private def saveInBackground(): Unit = {
    val snapshot = state.actions.toList
    writer.submit((() => save(snapshot)): Runnable)
  }

  private def save(actions: Seq[MacroAction]): Unit =
    Try(MacroFile.save(file, actions)).failed.foreach(e => state.log.warn(s"Autosave failed: ${e.getMessage}"))
}
