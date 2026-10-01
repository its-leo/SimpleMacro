package ui

import engine._
import model.MacroAction
import scalafx.beans.property.{BooleanProperty, ObjectProperty}
import scalafx.collections.ObservableBuffer

import java.io.File

/** State shared by all screens. Only modified on the JavaFX application thread unless noted. */
class AppState(val desktop: Desktop) {

  val actions: ObservableBuffer[MacroAction] = ObservableBuffer.empty[MacroAction]

  var selectedWindow: Option[TargetWindow] = None

  /** The file the macro was loaded from or last saved to. */
  val currentFile: ObjectProperty[Option[File]] = ObjectProperty(None)

  /** Thread-safe. */
  val log = new MacroLog()

  /** Thread-safe; set by the stop button and the global ESC hotkey. */
  val stop = new StopSignal

  /** True while a macro is running or scheduled. */
  val busy: BooleanProperty = BooleanProperty(false)

  val scheduler = new MacroScheduler

  def isSelectedWindowOpen: Boolean = selectedWindow.exists(_.isOpen)

  /** Stops a running macro and cancels a pending schedule. Must be called on the JavaFX thread. */
  def requestStop(): Unit = {
    stop.request()
    if (scheduler.cancel()) {
      busy.value = false
      log.warn("Schedule cancelled")
    }
  }
}
