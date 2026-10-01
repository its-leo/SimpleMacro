package ui

import engine.MacroLog.{Entry, Error, Warn}
import engine.MacroRunner
import engine.MacroRunner.{Completed, Failed, Stopped}
import scalafx.Includes._
import scalafx.application.Platform
import scalafx.beans.property.StringProperty
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.control._
import scalafx.scene.layout._
import scalafx.stage.Stage
import ui.UiKit._

import java.time.format.DateTimeFormatter
import java.time.{Duration, LocalDateTime}

/** Step 3: run the macro now or on a schedule and show the execution log. */
class ExecutionView(state: AppState, stage: Stage, onPrevious: () => Unit) {

  private val status = new Label(if (state.busy.value) "Macro is running..." else "Ready to execute") {
    style = "-fx-font-size: 15px;"
    wrapText = true
  }

  private val mode = StringProperty("now")

  private def modeButton(text: String, value: String) = new Button(text) {
    style <== mode.map(m => (if (m == value) "-fx-background-color: #4682B4; -fx-text-fill: white;" else "-fx-background-color: #D3D3D3;") + "-fx-font-size: 14px; -fx-padding: 6 15;")
    onAction = _ => mode.value = value
  }

  private val now = LocalDateTime.now()
  private val repeatSpinner = intSpinner(1, 10000, 1, spinnerWidth = 90)
  private val hourSpinner = intSpinner(0, 23, now.getHour, spinnerWidth = 65)
  private val minuteSpinner = intSpinner(0, 59, now.getMinute, spinnerWidth = 65)
  private val secondSpinner = intSpinner(0, 59, 0, spinnerWidth = 65)
  private val intervalSpinner = intSpinner(1, 1000, 1, spinnerWidth = 90)
  private val unitBox = new ComboBox[String](Seq("Seconds", "Minutes", "Hours")) {
    value = "Minutes"
  }

  // --- Log -------------------------------------------------------------------------

  private val logItems = ObservableBuffer.from(state.log.entries)
  private val logView = new ListView[Entry](logItems) {
    prefHeight = 170
    style = "-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12px;"
    cellFactory = (_: ListView[Entry]) => new ListCell[Entry](new javafx.scene.control.ListCell[Entry] {
      override def updateItem(entry: Entry, empty: Boolean): Unit = {
        super.updateItem(entry, empty)
        setText(if (empty || entry == null) null else entry.toString)
        setStyle(if (empty || entry == null) "" else entry.level match {
          case Error => "-fx-text-fill: #c62828;"
          case Warn => "-fx-text-fill: #e65100;"
          case _ => ""
        })
      }
    })
  }
  logView.scrollTo(logItems.size)

  private val logListener: Entry => Unit = entry => Platform.runLater {
    logItems += entry
    if (logItems.size > 1000) logItems.remove(0, logItems.size - 1000)
    logView.scrollTo(logItems.size - 1)
  }
  state.log.addListener(logListener)

  def dispose(): Unit = state.log.removeListener(logListener)

  // --- Execution ------------------------------------------------------------------

  private def setStatus(text: String): Unit = Platform.runLater(status.text = text)

  private def showResult(result: MacroRunner.Result, label: String = ""): Unit = {
    val prefix = if (label.nonEmpty) s"$label: " else ""
    result match {
      case Completed => status.text = s"${prefix}Macro execution completed"
      case Stopped => status.text = s"${prefix}Macro execution stopped"
      case Failed(message) =>
        status.text = s"${prefix}Macro failed: $message"
        showError(stage, "Macro failed", message)
    }
  }

  private def execute(): Unit = state.selectedWindow.filter(_.isOpen) match {
    case None =>
      showWarning(stage, "Window closed", "The selected window was closed. Please select a window.")
      onPrevious()
    case Some(window) =>
      state.stop.reset()
      // Snapshot the actions so later edits do not affect a running or scheduled macro
      val actions = state.actions.toList
      val runner = new MacroRunner(state.desktop, window, state.stop, state.log, setStatus)
      val repeat = valueOf(repeatSpinner)

      if (mode.value == "now") {
        state.busy.value = true
        val worker = new Thread(() => {
          val result = runner.run(actions, repeat)
          Platform.runLater {
            state.busy.value = false
            showResult(result)
          }
        }, "macro-worker")
        worker.setDaemon(true)
        worker.start()
      } else {
        val start = LocalDateTime.now().withHour(valueOf(hourSpinner)).withMinute(valueOf(minuteSpinner)).withSecond(valueOf(secondSpinner)).withNano(0)
        val amount = valueOf(intervalSpinner).toLong
        val interval = unitBox.value.value match {
          case "Seconds" => Duration.ofSeconds(amount)
          case "Minutes" => Duration.ofMinutes(amount)
          case _ => Duration.ofHours(amount)
        }
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
        state.busy.value = true

        lazy val first: LocalDateTime = state.scheduler.schedule(start, repeat, interval) { run =>
          val label = s"Run $run/$repeat"
          val result = runner.run(actions, 1, label)
          val proceed = result == Completed && run < repeat
          Platform.runLater {
            showResult(result, label)
            if (proceed) status.text = s"$label completed. Next run at ${engine.MacroScheduler.nextRun(first, interval, run).format(timeFormat)}"
          }
          result == Completed
        } { () =>
          Platform.runLater(state.busy.value = false)
        }
        status.text = s"Scheduled: first run at ${first.format(timeFormat)}, $repeat run(s) every $amount ${unitBox.value.value.toLowerCase}"
        state.log.info(status.text.value)
      }
  }

  val scene: Scene = new Scene {
    root = new BorderPane {
      top = new VBox(6) {
        padding = Insets(10, 15, 5, 15)
        children = Seq(
          heading("Step 3: Execute Actions"),
          new Label(s"Target window: ${state.selectedWindow.map(_.title).getOrElse("-")}  -  ${state.actions.size} action(s)") {
            style = "-fx-font-size: 13px;"
          },
          new Label("Press ESC at any time to stop the execution.") {
            style = "-fx-font-size: 14px; -fx-text-fill: #4CAF50;"
          }
        )
      }
      center = new VBox(12) {
        alignment = Pos.TopCenter
        padding = Insets(5, 15, 5, 15)
        children = Seq(
          new HBox(10) {
            alignment = Pos.Center
            children = Seq(modeButton("Execute Now", "now"), modeButton("Schedule", "schedule"))
          },
          new HBox(10) {
            alignment = Pos.Center
            children = Seq(new Label("Repeat:") {
              style = "-fx-font-size: 15px;"
            }, repeatSpinner, new Label("times"))
          },
          new VBox(10) {
            visible <== mode.isEqualTo("schedule")
            managed <== visible
            children = Seq(
              new HBox(10) {
                alignment = Pos.Center
                children = Seq(new Label("Start at:") {
                  style = "-fx-font-size: 15px;"
                }, hourSpinner, new Label(":"), minuteSpinner, new Label(":"), secondSpinner)
              },
              new HBox(10) {
                alignment = Pos.Center
                children = Seq(new Label("Every:") {
                  style = "-fx-font-size: 15px;"
                }, intervalSpinner, unitBox)
              }
            )
          },
          new HBox(10) {
            alignment = Pos.Center
            children = Seq(
              new Button("Execute") {
                style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 16px; -fx-padding: 8 22;"
                disable <== state.busy
                onAction = _ => execute()
              },
              new Button("Stop") {
                style = "-fx-background-color: #FF4136; -fx-text-fill: white; -fx-font-size: 16px; -fx-padding: 8 22;"
                disable <== !state.busy
                onAction = _ => state.requestStop()
              }
            )
          },
          status,
          new HBox(10) {
            alignment = Pos.CenterLeft
            children = Seq(
              new Label("Log") {
                style = "-fx-font-weight: bold;"
              },
              new Region {
                hgrow = Priority.Always
              },
              new Button("Clear") {
                onAction = _ => {
                  state.log.clear()
                  logItems.clear()
                }
              }
            )
          },
          logView
        )
      }
      bottom = new HBox(10) {
        alignment = Pos.CenterLeft
        padding = Insets(10, 15, 15, 15)
        children = Seq(new Button("Previous") {
          style = PrimaryStyle
          disable <== state.busy
          onAction = _ => onPrevious()
        })
      }
    }
  }
}
