package util

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.{HWND, RECT}
import com.sun.jna.platform.{DesktopWindow, WindowUtils}
import cv.Image.agdImageBuffer
import javafx.animation.Animation
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleStringProperty
import javafx.embed.swing.SwingFXUtils
import javafx.scene.input.MouseButton
import scalafx.application.JFXApp3.PrimaryStage
import scalafx.application.{JFXApp3, Platform}
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.canvas.Canvas
import scalafx.scene.control.Alert.AlertType
import scalafx.scene.control._
import scalafx.scene.image.{ImageView, Image => FXImage}
import scalafx.scene.layout._
import scalafx.scene.paint.Color
import scalafx.scene.text.Font
import scalafx.stage.{Screen, Stage}

import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.awt.{Dimension, Rectangle, Robot}
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{Duration, LocalDate, LocalDateTime, LocalTime}
import java.util.concurrent.{Executors, TimeUnit}
import javax.swing.filechooser.FileSystemView
import scala.jdk.CollectionConverters._

object Client extends JFXApp3 {

  private var selectedWindow: Option[DesktopWindow] = None

  case class ClickPosition(x: Int, y: Int, windowDimension: Dimension, clickSettings: ClickSettings = ClickSettings())

  case class ClickSettings(button: String = "left", duration: Int = 500, clicks: Int = 1, mouseSpeed: Double = 1.0)

  case class Step(step: String, capturedImageOption: Option[BufferedImage] = None, clickPositionOption: Option[ClickPosition] = None, typeTextOption: Option[String] = None, waitSecondsOption: Option[Int] = None, clickSettings: ClickSettings = ClickSettings())

  private val macroSteps: ObservableBuffer[Step] = ObservableBuffer.empty[Step]


  private var currentStep = 1

  def showStep(stepNumber: Int): Unit = {
    currentStep = stepNumber
    stage.scene = stepNumber match {
      case 1 => createStep1Scene()
      case 2 => createStep2Scene()
      case 3 => createStep3Scene()
      case _ => throw new IllegalArgumentException(s"Invalid step number: $stepNumber")
    }
  }

  override def start(): Unit = {
    stage = new PrimaryStage {
      title = "SimpleMacro"
      width = 700
      height = 500
    }
    showStep(1)
  }


  private def createStep1Scene(): Scene = {
    macroSteps.clear()

    def getIcon(path: String): BufferedImage = {
      val icon = FileSystemView.getFileSystemView.getSystemIcon(new File(path))
      val scaleFactor = 1.3
      val width = (icon.getIconWidth * scaleFactor).toInt
      val height = (icon.getIconHeight * scaleFactor).toInt
      val bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      val g = bi.createGraphics
      g.setComposite(java.awt.AlphaComposite.Clear)
      g.fillRect(0, 0, width, height)
      g.setComposite(java.awt.AlphaComposite.SrcOver)
      icon.paintIcon(null, g, 0, 0)
      g.dispose()
      bi
    }

    case class WindowItem(window: DesktopWindow, icon: BufferedImage)

    def getWindowItems: Array[WindowItem] = {

      WindowUtils.getAllWindows(true).asScala
        .filter(w => w.getTitle.nonEmpty && !w.getTitle.contains("Task Manager"))
        .tail
        .map(w => WindowItem(w, Option(WindowUtils.getWindowIcon(w.getHWND)).getOrElse(getIcon(w.getFilePath))))
        .filterNot(_.icon == null)
        .toArray
    }

    val filteredItems = ObservableBuffer.from(getWindowItems)

    val listView = new ListView[WindowItem](filteredItems) {
      vgrow = Priority.Always
      cellFactory = (_: ListView[WindowItem]) => new ListCell[WindowItem] {
        item.onChange { (_, _, windowItem) =>
          if (windowItem != null) {
            val fxImage = SwingFXUtils.toFXImage(windowItem.icon, null)
            val imageView = new ImageView(new FXImage(fxImage))
            imageView.setPreserveRatio(true)
            imageView.setFitHeight(24)
            graphic = imageView
            text = windowItem.window.getTitle.split(" - ").last
            style = "-fx-font-size: 14px;"
          } else {
            graphic = null
            text = null
          }
        }
        prefHeight = 40
      }
    }

    val refreshTimeline = new javafx.animation.Timeline(
      new javafx.animation.KeyFrame(
        javafx.util.Duration.millis(5000),
        (_: javafx.event.ActionEvent) => if (currentStep == 1) {
          Platform.runLater {
            // Save the current selection
            val selectedItem = listView.getSelectionModel.getSelectedItem

            filteredItems.clear()
            filteredItems.addAll(getWindowItems)

            // Restore the selection if the item still exists
            if (selectedItem != null) {
              val newIndex = filteredItems.indexWhere(_.window.getHWND == selectedItem.window.getHWND)
              if (newIndex >= 0) {
                listView.getSelectionModel.select(newIndex)
              }
            }
          }
        }
      )
    )

    refreshTimeline.setCycleCount(Animation.INDEFINITE)
    refreshTimeline.play()


    new Scene(700, 500) {
      root = new BorderPane {
        top = new HBox {
          alignment = Pos.CenterLeft
          padding = Insets(10, 0, 10, 15)
          children = Seq(
            new Label("Step 1: Select a Window") {
              style = "-fx-font-size: 28px; -fx-font-weight: bold;"
            }
          )
        }
        center = new VBox(10) {
          alignment = Pos.Center
          padding = Insets(0, 15, 15, 15)
          children = Seq(
            new TextField {
              promptText = "Filter windows..."
              style = "-fx-font-size: 14px;"
              onKeyReleased = (e: javafx.scene.input.KeyEvent) => {
                val searchTerm = text.value.toLowerCase
                filteredItems.clear()
                filteredItems.addAll(getWindowItems.filter(_.window.getTitle.toLowerCase.contains(searchTerm)))
              }
            },
            listView
          )
        }
        bottom = new HBox {
          alignment = Pos.BottomRight
          padding = Insets(15)
          children = Seq(
            new Button("Next") {
              style = "-fx-background-color: #4682B4; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
              onAction = _ => {
                val selectedItem = this.getScene.lookup(".list-view").asInstanceOf[javafx.scene.control.ListView[WindowItem]].getSelectionModel.getSelectedItem
                if (selectedItem != null) {
                  selectedWindow = Some(selectedItem.window)
                  refreshTimeline.stop()
                  showStep(2)
                }
              }
            }
          )
        }
      }
    }
  }

  private def createStep2Scene(): Scene = {
    new Scene(700, 500) {
      root = new BorderPane {
        top = new HBox {
          alignment = Pos.CenterLeft
          padding = Insets(10, 0, 10, 15)
          children = Seq(
            new Label("Step 2: Define Steps") {
              style = "-fx-font-size: 28px; -fx-font-weight: bold;"
            }
          )
        }
        center = new HBox(20) {
          alignment = Pos.Center
          padding = Insets(0, 15, 15, 15)
          children = Seq(
            new VBox(20) {
              alignment = Pos.Center
              children = Seq(
                new GridPane {
                  hgap = 10
                  vgap = 10
                  alignment = Pos.Center

                  val actionButtons = List(
                    ("Click Position", "mouse_icon.png", () => recordMousePosition()),
                    ("Click Visual", "click_icon.png", () => recordImage()),
                    ("Wait", "time-icon.png", () => selectWaitSeconds()),
                    ("Type Text", "text_icon.png", () => typeText()),
                  )

                  actionButtons.zipWithIndex.foreach { case ((text, iconPath, action), index) =>
                    val button = new Button(text) {
                      style = "-fx-background-color: #4682B4; -fx-text-fill: white; -fx-font-size: 14px;"
                      graphic = new ImageView(new FXImage(getClass.getResourceAsStream(s"/icons/$iconPath"))) {
                        fitHeight = 24
                        fitWidth = 24
                        preserveRatio = true
                      }
                      onAction = _ => action()
                      maxWidth = Double.MaxValue
                      maxHeight = Double.MaxValue
                    }
                    add(button, index % 2, index / 2)
                  }
                }
              )
            },
            new VBox(20) {
              alignment = Pos.Center
              children = Seq(
                new ListView[Step](macroSteps) {
                  prefWidth = 350
                  prefHeight = 400
                  cellFactory = _ => new ListCell[Step] {
                    prefHeight = 40
                    item.onChange { (_, _, newValue) =>
                      if (newValue != null) {
                        val index = this.getIndex
                        val removeButton = new Button("X") {
                          style = "-fx-background-color: #FF4136; -fx-text-fill: white; -fx-font-size: 14px;"
                          onAction = _ => {
                            if (index >= 0 && index < macroSteps.size) {
                              macroSteps.remove(index)
                            }
                          }
                        }

                        val settingsButton = new Button("⚙") {
                          style = "-fx-background-color: #4682B4; -fx-text-fill: white; -fx-font-size: 14px;"
                          visible = true
                          onAction = _ => {
                            val step = macroSteps(index)
                            step.step match {
                              case "Click Visual" | "Click Position" =>
                                showClickSettingsDialog(index)
                              case "Type Text" =>
                                typeText(step.typeTextOption, Some(index))
                              case "Wait" =>
                                selectWaitSeconds(step.waitSecondsOption, Some(index))
                              case _ =>
                              // Handle other step types if needed
                            }
                          }
                        }

                        val hbox = new HBox(5) {
                          alignment = Pos.CenterLeft
                          children = Seq(
                            new Label(newValue.step) {
                              style = "-fx-font-size: 14px;"
                            },
                            new Region() {
                              hgrow = Priority.Always
                            },
                            settingsButton,
                            removeButton
                          )
                        }

                        val vbox = new VBox(5) {
                          alignment = Pos.Center
                          children = Seq(hbox)
                        }

                        // Handle different types of steps
                        newValue.capturedImageOption.foreach { img =>
                          val fxImage = SwingFXUtils.toFXImage(img, null)
                          val imageView = new ImageView(new FXImage(fxImage)) {
                            preserveRatio = true
                            fitHeight = 30
                            fitWidth = 100
                          }
                          hbox.children.add(1, imageView)
                          vbox.alignment = Pos.CenterLeft
                        }

                        newValue.clickPositionOption.foreach { clickPos =>
                          val positionLabel = new Label(s"(${clickPos.x}, ${clickPos.y})") {
                            maxWidth = Double.MaxValue
                            alignment = Pos.CenterLeft
                            style = "-fx-font-size: 14px;"
                          }
                          hbox.children.add(1, positionLabel)
                        }

                        newValue.typeTextOption.foreach { text =>
                          val textLabel = new Label(s"\"$text\"") {
                            maxWidth = Double.MaxValue
                            alignment = Pos.CenterLeft
                            style = "-fx-font-size: 14px;"
                          }
                          hbox.children.add(1, textLabel)
                        }

                        newValue.waitSecondsOption.foreach { totalSeconds =>
                          val hours = totalSeconds / 3600
                          val minutes = (totalSeconds % 3600) / 60
                          val seconds = totalSeconds % 60

                          val timeComponents = Seq(
                            if (hours > 0) s"$hours hour${if (hours > 1) "s" else ""}" else "",
                            if (minutes > 0) s"$minutes minute${if (minutes > 1) "s" else ""}" else "",
                            if (seconds > 0) s"$seconds second${if (seconds > 1) "s" else ""}" else ""
                          ).filter(_.nonEmpty)

                          val timeString = timeComponents match {
                            case Nil => "0 seconds"
                            case list => list.mkString(" ")
                          }

                          val textLabel = new Label(timeString) {
                            style = "-fx-font-size: 14px;"
                          }
                          hbox.children.add(1, textLabel)
                        }

                        graphic = vbox
                        text = null
                      } else {
                        graphic = null
                        text = null
                      }
                    }
                  }
                }
              )
            }
          )
        }
        bottom = new HBox(10) {
          alignment = Pos.Center
          padding = Insets(15)
          children = Seq(
            new Button("Previous") {
              style = "-fx-background-color: #4682B4; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
              onAction = _ => showStep(1)
            },
            new Button("Next") {
              style = "-fx-background-color: #4682B4; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
              onAction = _ => if (macroSteps.nonEmpty) showStep(3) else {
                Platform.runLater {
                  new Alert(AlertType.Warning) {
                    title = "No steps defined"
                    headerText = ""
                    contentText = s"You have to define at least one step."
                  }.showAndWait()
                }
              }
            }
          )
        }
      }
    }
  }

  private def createStep3Scene(): Scene = {
    val currentStepLabel = new Label("Ready to execute")
    currentStepLabel.style = "-fx-font-size: 16px;"

    val executeNowButton = new Button("Execute Now")
    val scheduleButton = new Button("Schedule")

    val selectedMode = new SimpleStringProperty("ExecuteNow")
    // or "ExecuteNow"
    def updateButtonStyles(): Unit = {
      executeNowButton.style = {if (selectedMode.get() == "ExecuteNow") "-fx-background-color: #4682B4; -fx-text-fill: white;" else "-fx-background-color: #D3D3D3;"} + "-fx-font-size: 14px; -fx-padding: 8 15;"
      scheduleButton.style = {if (selectedMode.get() == "Schedule") "-fx-background-color: #4682B4; -fx-text-fill: white;" else "-fx-background-color: #D3D3D3;"} + "-fx-font-size: 14px; -fx-padding: 8 15;"
    }

    executeNowButton.onAction = _ => {
      selectedMode.set("ExecuteNow")
      updateButtonStyles()
    }

    scheduleButton.onAction = _ => {
      selectedMode.set("Schedule")
      updateButtonStyles()
    }

    updateButtonStyles()

    val hourSpinner = new Spinner[Int](0, 23, 0){
      prefWidth = 60
    }
    val minuteSpinner = new Spinner[Int](0, 59, 0){
      prefWidth = 60
    }
    val secondSpinner = new Spinner[Int](0, 59, 0){
      prefWidth = 60
    }

    val repeatCountSpinner = new Spinner[Int](1, 1000, 1){
      prefWidth = 70
    }
    val repeatIntervalSpinner = new Spinner[Int](1, 1000, 1){
      prefWidth = 70
    }
    val repeatUnitComboBox = new ComboBox[String](ObservableBuffer("Seconds", "Minutes", "Hours"))
    repeatUnitComboBox.value = "Minutes"

    val executeButton = new Button("Execute") {
      style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 16px; -fx-padding: 10 20;"
      onAction = _ => if (isSelectedWindowOpen) {
        val repeatCount = repeatCountSpinner.value.value
        if (selectedMode.get() == "ExecuteNow") {

          executeMacroNow(currentStepLabel, repeatCount)
        } else {
          val now = LocalDateTime.now()
          val scheduledTime = now
            .withHour(hourSpinner.value.value)
            .withMinute(minuteSpinner.value.value)
            .withSecond(secondSpinner.value.value)
          val repeatInterval = repeatIntervalSpinner.value.value
          val repeatUnit = repeatUnitComboBox.value.value
          executeMacroWithFlexibleSchedule(currentStepLabel, repeatCount, scheduledTime, repeatCount, repeatInterval, repeatUnit)
        }
      }
    }

    new Scene(700, 500) {
      root = new BorderPane {
        top = new HBox {
          alignment = Pos.CenterLeft
          padding = Insets(20, 0, 20, 30)
          children = Seq(
            new Label("Step 3: Execute Macro") {
              style = "-fx-font-size: 28px; -fx-font-weight: bold;"
            }
          )
        }
        center = new VBox(20) {
          alignment = Pos.Center
          padding = Insets(0, 30, 0, 30)
          children = Seq(
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(executeNowButton, scheduleButton)
            },
            new VBox(10) {
              children = Seq(
                new HBox(10) {
                  alignment = Pos.Center
                  children = Seq(
                    new Label("Repeat:") {
                      style = "-fx-font-size: 16px;"
                    },
                    repeatCountSpinner,
                    new Label("times")
                  )
                }
              )
            },
            new VBox(10) {
              visible <== selectedMode.isEqualTo("Schedule")
              children = Seq(
                new HBox(10) {
                  alignment = Pos.Center
                  children = Seq(
                    new Label("Time:") {
                      style = "-fx-font-size: 16px;"
                    },
                    hourSpinner,
                    new Label(":"),
                    minuteSpinner,
                    new Label(":"),
                    secondSpinner
                  )
                },
                new HBox(10) {
                  alignment = Pos.Center
                  children = Seq(
                    new Label("Every:") {
                      style = "-fx-font-size: 16px;"
                    },
                    repeatIntervalSpinner,
                    repeatUnitComboBox
                  )
                }
              )
            },
            executeButton,
            new VBox(10) {
              alignment = Pos.Center
              children = Seq(currentStepLabel)
            }
          )
        }
        bottom = new HBox(10) {
          alignment = Pos.CenterLeft
          padding = Insets(20)
          children = Seq(
            new Button("Previous") {
              style = "-fx-background-color: #4682B4; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
              onAction = _ => showStep(2)
            }
          )
        }
      }
    }
  }

  private def executeMacroWithFlexibleSchedule(currentStepLabel: Label, loopCount: Int, scheduledTime: LocalDateTime, repeatCount: Int, repeatInterval: Int, repeatUnit: String): Unit = {
    val now = LocalDateTime.now()
    var nextRun = if (scheduledTime.isBefore(now)) scheduledTime.plusDays(1) else scheduledTime

    val scheduler = Executors.newScheduledThreadPool(1)

    val task = new Runnable {
      var executionCount = 0

      override def run(): Unit = {
        if (executionCount < repeatCount) {
          Platform.runLater {
            currentStepLabel.text = s"Executing scheduled macro (${executionCount + 1}/$repeatCount)"
          }
          executeMacroNow(currentStepLabel, loopCount)
          executionCount += 1

          // Schedule next run
          val delay = repeatUnit match {
            case "Seconds" => Duration.ofSeconds(repeatInterval)
            case "Minutes" => Duration.ofMinutes(repeatInterval)
            case "Hours" => Duration.ofHours(repeatInterval)
          }
          nextRun = nextRun.plus(delay)
          scheduler.schedule(this, Duration.between(LocalDateTime.now(), nextRun).toMillis, TimeUnit.MILLISECONDS)
        } else {
          scheduler.shutdown()
        }
      }
    }

    val initialDelay = Duration.between(now, nextRun)
    scheduler.schedule(task, initialDelay.toMillis, TimeUnit.MILLISECONDS)

    Platform.runLater {
      currentStepLabel.text = s"Scheduled to start at ${nextRun.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}, repeating $repeatCount times"
    }
  }

  private def isSelectedWindowOpen: Boolean = selectedWindow match {
    case Some(window) => User32.INSTANCE.IsWindow(window.getHWND)
    case None =>
      println("Window closed")
      Platform.runLater {
        macroSteps.clear()
        new Alert(AlertType.Warning) {
          title = "Window closed"
          headerText = "The selected window was closed."
          contentText = "Please select a window"
        }.showAndWait()
        showStep(1)
      }
      false
  }

  private def executeMacroWithSchedule(currentStepLabel: Label, loopCount: Int, scheduledDateTime: LocalDateTime): Unit = {
    val now = LocalDateTime.now()
    val delay = Duration.between(now, scheduledDateTime)

    if (delay.isNegative) {
      new Alert(AlertType.Warning) {
        title = "Invalid Schedule"
        headerText = "The scheduled time is in the past."
        contentText = "Please select a future date and time."
      }.showAndWait()
      return
    }

    currentStepLabel.text = s"Scheduled for ${scheduledDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}"

    val scheduler = Executors.newSingleThreadScheduledExecutor()
    scheduler.schedule(new Runnable {
      override def run(): Unit = {
        Platform.runLater {
          currentStepLabel.text = "Executing macro..."
        }
        for (i <- 1 to loopCount) {
          executeMacro(currentStepLabel, i, loopCount)
          if (i < loopCount) {
            Thread.sleep(5000) // 5-second delay between loops
          }
        }
        scheduler.shutdown()
      }
    }, delay.toMillis, TimeUnit.MILLISECONDS)
  }

  private def executeMacroNow(currentStepLabel: Label, loopCount: Int): Unit = {
    new Thread(() => {

      for (i <- 1 to loopCount) {
        executeMacro(currentStepLabel, i, loopCount)
        if (i < loopCount) {
          Thread.sleep(5000) // 5-second delay between loops
        }
      }
    }).start()
  }

  private def executeMacroDaily(currentStepLabel: Label, loopCount: Int, scheduledTime: LocalTime, daysToRun: Int): Unit = {
    val scheduler = Executors.newScheduledThreadPool(1)

    val now = LocalDateTime.now()
    var nextRun = LocalDateTime.of(LocalDate.now(), scheduledTime)
    if (now.isAfter(nextRun)) {
      nextRun = nextRun.plusDays(1)
    }

    val initialDelay = Duration.between(now, nextRun)

    val dailyTask = new Runnable {
      var daysRun = 0

      override def run(): Unit = {
        if (daysRun < daysToRun) {
          Platform.runLater {
            currentStepLabel.text = s"Executing daily macro (Day ${daysRun + 1}/${daysToRun})"
          }
          for (i <- 1 to loopCount) {
            executeMacro(currentStepLabel, i, loopCount)
            if (i < loopCount) {
              Thread.sleep(5000) // 5-second delay between loops
            }
          }
          daysRun += 1
        } else {
          scheduler.shutdown()
        }
      }
    }

    scheduler.scheduleAtFixedRate(dailyTask, initialDelay.toMillis, 24 * 60 * 60 * 1000, TimeUnit.MILLISECONDS)

    Platform.runLater {
      currentStepLabel.text = s"Scheduled to run daily at ${scheduledTime.format(DateTimeFormatter.ofPattern("HH:mm"))} for $daysToRun days"
    }
  }

  private def executeMacro(currentStepLabel: Label, currentLoop: Int, totalLoops: Int): Unit = {

    val robot = new Robot()
    val totalSteps = macroSteps.size


    def performClick(x: Int, y: Int, step: Step): Unit = {
      // Move the mouse in a human-like manner

      Mouse.moveHumanLike(robot, x, y, step.clickSettings.mouseSpeed)

      // Perform the click based on settings
      val button = step.clickSettings.button match {
        case "left" => InputEvent.BUTTON1_DOWN_MASK
        case "right" => InputEvent.BUTTON3_DOWN_MASK
        //case "Middle" => InputEvent.BUTTON2_DOWN_MASK //TODO implement middle
      }

      val clickCount = step.clickSettings.clicks
      for (_ <- 1 to clickCount) {
        robot.mousePress(button)
        robot.mouseRelease(button)
        if (clickCount > 1) Thread.sleep(50) // Small delay between clicks for double-click
      }
    }


    def executeStep(index: Int): Unit = {
      if (index < macroSteps.size) {
        val step = macroSteps(index)
        Platform.runLater {
          currentStepLabel.text = s"Loop $currentLoop/$totalLoops - Executing step ${index + 1} of $totalSteps: ${step.step}"
        }

        step.step match {
          case "Click Position" =>
            step.clickPositionOption.foreach { clickPosition =>

              selectedWindow.foreach { window =>
                val hwnd = window.getHWND

                // Get the current window position and size
                val currentBounds = WindowUtils.getWindowLocationAndSize(hwnd)

                // Adjust the window size if it's different from the config
                if (currentBounds.width != clickPosition.windowDimension.width ||
                  currentBounds.height != clickPosition.windowDimension.height) {
                  User32.INSTANCE.SetWindowPos(
                    hwnd,
                    null,
                    currentBounds.x,
                    currentBounds.y,
                    clickPosition.windowDimension.width,
                    clickPosition.windowDimension.height,
                    0x0004 | 0x0010 // SWP_NOZORDER | SWP_NOACTIVATE
                  )
                }
                // Bring the window to front
                windowToFront(hwnd)

                // Get the window position
                val windowBounds = WindowUtils.getWindowLocationAndSize(hwnd)

                // Calculate the absolute click position
                val clickX = windowBounds.x + clickPosition.x
                val clickY = windowBounds.y + clickPosition.y

                performClick(clickX, clickY, step)

                // Wait after the click
                Thread.sleep(step.clickSettings.duration)
              }
            }


          case "Click Visual" =>
            step.capturedImageOption.foreach { capturedImage =>

              selectedWindow.foreach { window =>

                val hwnd = window.getHWND
                windowToFront(hwnd)

                val rect = new RECT()
                User32.INSTANCE.GetWindowRect(hwnd, rect)

                val clientRect = new RECT()
                User32.INSTANCE.GetClientRect(hwnd, clientRect)

                val borderWidth = (rect.right - rect.left - clientRect.right) / 2
                val titleBarHeight = rect.bottom - rect.top - clientRect.bottom - borderWidth

                val x = rect.left + borderWidth
                val y = rect.top + titleBarHeight

                val screenRect = new Rectangle(x, y, clientRect.right, clientRect.bottom)

                val screenCapture = robot.createScreenCapture(screenRect)

                // Perform template matching to find the captured image
                screenCapture.findBestMatch(capturedImage) match {
                  case Some(match0) =>
                    val matchRect = match0.rect

                    // Calculate the center of the matched rectangle
                    val centerX = x + matchRect.x + matchRect.width / 2
                    val centerY = y + matchRect.y + matchRect.height / 2

                    performClick(centerX, centerY, step)
                  case None => {
                    println("Unable to find the captured image in the window")
                    Platform.runLater {
                      new Alert(AlertType.Error) {
                        title = "Image Not Found"
                        headerText = "The captured image was not found in the window."
                      }.showAndWait()
                    }

                  }
                }
              }
            }

          case "Type Text" =>
            step.typeTextOption.foreach { text =>
              for (char <- text) {
                val keyCode = char.toUpper.toInt
                robot.keyPress(keyCode)
                robot.keyRelease(keyCode)
                Thread.sleep(50) // Small delay between key presses
              }
            }

          case "Wait" =>
            step.waitSecondsOption.foreach { seconds =>
              Thread.sleep(seconds * 1000)
            }

          case _ =>
          // Handle other step types if needed
        }



        // Schedule the next step execution after a delay
        Platform.runLater {
          executeStep(index + 1)
        }
      } else {
        // All steps completed
        Platform.runLater {
          currentStepLabel.text = if (currentLoop == totalLoops) "Macro execution completed" else s"Loop $currentLoop/$totalLoops completed"

        }
      }
    }


    if (macroSteps.nonEmpty) executeStep(0)
  }




  //--------------------------------------------------------------------------


  //can you implement a new action called "if" that represents a condition which will block all subsequent steps if fulfilled (or not fulfilled). The trigger for the if should be visual and reuse recordImage

  def windowToFront(hwnd: HWND): Unit = {
    User32.INSTANCE.ShowWindow(hwnd, User32.SW_SHOWDEFAULT)
    User32.INSTANCE.SetForegroundWindow(hwnd)
    Thread.sleep(400)
  }


  private def recordImage(): Unit = if (isSelectedWindowOpen) {
    selectedWindow.foreach { window =>

      windowToFront(window.getHWND)

      // Create a transparent overlay stage
      val screenBounds = Screen.primary.bounds
      val overlayStage: Stage = new Stage {
        title = "Select Area to Capture"
        fullScreen = true
        initStyle(javafx.stage.StageStyle.TRANSPARENT) // Add this line
        scene = new Scene(screenBounds.width, screenBounds.height) {
          fill = Color.Transparent
          val canvas = new Canvas(screenBounds.width, screenBounds.height)
          root = new StackPane {
            children = canvas
            style = "-fx-background-color: rgba(0, 0, 0, 0.1);" // Add this line

          }

          onKeyPressed = (e: javafx.scene.input.KeyEvent) => {
            if (e.getCode == javafx.scene.input.KeyCode.ESCAPE) {
              stage.toFront()
              close()
            }
          }

          var startX = 0.0
          var startY = 0.0
          var endX = 0.0
          var endY = 0.0
          var isDragging = false

          canvas.onMousePressed = e => {
            startX = e.getX
            startY = e.getY
            isDragging = true
          }

          canvas.onMouseDragged = e => {
            if (isDragging) {
              endX = e.getX
              endY = e.getY
              redrawSelection()
            }
          }

          canvas.onMouseReleased = e => {
            if (isDragging) {
              endX = e.getX
              endY = e.getY
              isDragging = false
              captureSelectedArea()
              stage.toFront()
              close()
            }
          }

          def redrawSelection(): Unit = {
            val gc = canvas.graphicsContext2D
            gc.clearRect(0, 0, canvas.width.value, canvas.height.value)
            gc.setStroke(Color.Red)
            gc.setLineWidth(2)

            val width = Math.min(Math.abs(endX - startX), 400)
            val height = Math.min(Math.abs(endY - startY), 400)

            gc.strokeRect(
              Math.min(startX, endX),
              Math.min(startY, endY),
              width,
              height
            )
          }

          def captureSelectedArea(): Unit = {
            val x = Math.min(startX, endX).toInt
            val y = Math.min(startY, endY).toInt
            val width = Math.min(Math.abs(endX - startX).toInt, 400)
            val height = Math.min(Math.abs(endY - startY).toInt, 400)

            if (width + height > 0) {
              val robot = new Robot()
              val capturedImage = robot.createScreenCapture(new Rectangle(x, y, width, height))

              macroSteps.addOne(Step("Click Visual", capturedImageOption = Some(capturedImage)))

            }

          }
        }
      }

      overlayStage.showAndWait()
    }
  }


  private def recordMousePosition(): Unit = if (isSelectedWindowOpen) {
    selectedWindow.foreach { window =>
      val hwnd = window.getHWND

      windowToFront(hwnd)

      // Create a transparent overlay stage
      val screenBounds = Screen.primary.bounds
      val overlayStage: Stage = new Stage {
        title = "Click Position"
        fullScreen = true
        initStyle(javafx.stage.StageStyle.TRANSPARENT)
        scene = new Scene(screenBounds.width, screenBounds.height) {
          fill = Color.Transparent
          val canvas = new Canvas(screenBounds.width, screenBounds.height)
          root = new StackPane {
            children = canvas
            style = "-fx-background-color: rgba(0, 0, 0, 0.1);"
          }

          onKeyPressed = (e: javafx.scene.input.KeyEvent) => {
            if (e.getCode == javafx.scene.input.KeyCode.ESCAPE) {
              stage.toFront()
              close()
            }
          }

          canvas.onMouseMoved = e => {
            val gc = canvas.graphicsContext2D
            gc.clearRect(0, 0, canvas.width.value, canvas.height.value)

            val windowBounds = WindowUtils.getWindowLocationAndSize(hwnd)
            val relativeX = e.getX - windowBounds.x
            val relativeY = e.getY - windowBounds.y

            if (relativeX >= 0 && relativeX < windowBounds.width && relativeY >= 0 && relativeY < windowBounds.height) {
              // Mouse is inside the window
              gc.setFill(Color.Red)
              gc.fillOval(e.getX - 5, e.getY - 5, 10, 10)
              gc.setFill(Color.White)
              gc.fillText(s"Rel: (${relativeX.toInt}, ${relativeY.toInt})", e.getX + 10, e.getY - 5)
            }
          }

          canvas.onMouseClicked = e => {
            val windowBounds = WindowUtils.getWindowLocationAndSize(hwnd)
            val relativeX = e.getX - windowBounds.x
            val relativeY = e.getY - windowBounds.y

            val clickPosition = ClickPosition(relativeX.toInt, relativeY.toInt, windowBounds.getSize)

            val mouseButton = e.getButton match {
              case MouseButton.SECONDARY => "right"
              case _ => "left"
            }
            macroSteps.addOne(Step("Click Position", clickPositionOption = Some(clickPosition), clickSettings = ClickSettings(button = mouseButton)))

            stage.toFront()
            close()
          }
        }
      }

      overlayStage.showAndWait()
    }
  }

  private def typeText(existingText: Option[String] = None, stepIndex: Option[Int] = None): Unit = {
    val dialog = new Stage {
      title = "Type Text"
      initModality(javafx.stage.Modality.APPLICATION_MODAL)
      initOwner(stage)

      val textField = new TextField {
        prefWidth = 300
        text = existingText.getOrElse("")
      }

      scene = new Scene {
        content = new VBox(20) {
          alignment = Pos.Center
          padding = Insets(20)
          children = Seq(
            new Label("Enter the text to type:") {
              style = "-fx-font-size: 14px;"
            },
            textField,
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(
                new Button("OK") {
                  style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px;"
                  onAction = _ => {
                    val text = textField.text.value
                    if (text.nonEmpty) {
                      stepIndex match {
                        case Some(index) => macroSteps(index) = Step("Type Text", typeTextOption = Some(text))
                        case None => macroSteps.addOne(Step("Type Text", typeTextOption = Some(text)))
                      }
                    }
                    close()
                  }
                },
                new Button("Cancel") {
                  style = "-fx-background-color: #FF4136; -fx-text-fill: white; -fx-font-size: 14px;"
                  onAction = _ => close()
                }
              )
            }
          )
        }
      }
    }
    dialog.showAndWait()
  }

  private def selectWaitSeconds(existingSeconds: Option[Int] = None, stepIndex: Option[Int] = None): Unit = {
    val dialog = new Stage {
      title = "Set Wait Time"
      initModality(javafx.stage.Modality.APPLICATION_MODAL)
      initOwner(stage)

      val initialSeconds = existingSeconds.getOrElse(0)
      val hoursSpinner = new Spinner[Int](0, 23, initialSeconds / 3600) {
        editable = true
        prefWidth = 70
      }
      val minutesSpinner = new Spinner[Int](0, 59, (initialSeconds % 3600) / 60) {
        editable = true
        prefWidth = 70
      }
      val secondsSpinner = new Spinner[Int](0, 59, initialSeconds % 60) {
        editable = true
        prefWidth = 70
      }

      minutesSpinner.valueProperty().addListener((_, _, newValue) => {
        if (newValue.intValue == 60) {
          minutesSpinner.getValueFactory.setValue(0)
          hoursSpinner.increment()
        } else if (newValue.intValue == -1) {
          minutesSpinner.getValueFactory.setValue(59)
          hoursSpinner.decrement()
        }
      })

      secondsSpinner.valueProperty().addListener((_, _, newValue) => {
        if (newValue.intValue == 60) {
          secondsSpinner.getValueFactory.setValue(0)
          minutesSpinner.increment()
        } else if (newValue.intValue == -1) {
          secondsSpinner.getValueFactory.setValue(59)
          minutesSpinner.decrement()
        }
      })


      val clockCanvas = new Canvas(150, 150)
      val gc = clockCanvas.graphicsContext2D

      def updateSpinners(): Unit = {
        var totalSeconds = hoursSpinner.value.value * 3600 + minutesSpinner.value.value * 60 + secondsSpinner.value.value

        val hours = totalSeconds / 3600
        totalSeconds %= 3600
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        hoursSpinner.getValueFactory.setValue(hours)
        minutesSpinner.getValueFactory.setValue(minutes)
        secondsSpinner.getValueFactory.setValue(seconds)

        updateClock()
      }

      def updateClock(): Unit = {
        val totalMinutes = hoursSpinner.value.value * 60 + minutesSpinner.value.value
        val angle = 360.0 * totalMinutes / (60) // Angle for a 60-minute clock
        val hours = totalMinutes / 60

        gc.clearRect(0, 0, 150, 150)

        // Draw clock face
        gc.setFill(Color.LightGray)
        gc.fillOval(0, 0, 150, 150)

        // Color the area from 12 to the current position
        val baseColor = Color.rgb(135, 206, 250) // Light blue
        val intensity = Math.min(1.0, 0.2 + (hours * 0.2)) // Increase intensity for each hour, max at 1.0
        val fillColor = baseColor.deriveColor(0, 1, intensity, 0.5)

        gc.setFill(fillColor)
        gc.beginPath()
        gc.moveTo(75, 75)
        gc.lineTo(75, 2) // Top of the clock (12 o'clock position)
        gc.arc(75, 75, 73, 73, 90, -angle)
        gc.lineTo(75, 75)
        gc.closePath()
        gc.fill()

        // Draw minute markers
        gc.setStroke(Color.Black)
        gc.setLineWidth(1)
        for (i <- 0 until 60) {
          val markerAngle = i * 6 // 360 degrees / 60 minutes = 6 degrees per minute
          val startX = 75 + 70 * Math.sin(Math.toRadians(markerAngle))
          val startY = 75 - 70 * Math.cos(Math.toRadians(markerAngle))
          val endX = 75 + 73 * Math.sin(Math.toRadians(markerAngle))
          val endY = 75 - 73 * Math.cos(Math.toRadians(markerAngle))
          gc.strokeLine(startX, startY, endX, endY)
        }

        // Draw hour markers
        gc.setLineWidth(2)
        for (i <- 0 until 12) {
          val markerAngle = i * 30 // 360 degrees / 12 hours = 30 degrees per hour
          val startX = 75 + 68 * Math.sin(Math.toRadians(markerAngle))
          val startY = 75 - 68 * Math.cos(Math.toRadians(markerAngle))
          val endX = 75 + 73 * Math.sin(Math.toRadians(markerAngle))
          val endY = 75 - 73 * Math.cos(Math.toRadians(markerAngle))
          gc.strokeLine(startX, startY, endX, endY)
        }

        // Draw clock hand
        gc.setStroke(Color.Blue)
        gc.setLineWidth(3)
        gc.strokeLine(75, 75,
          75 + 65 * Math.sin(Math.toRadians(angle)),
          75 - 65 * Math.cos(Math.toRadians(angle)))

        // Draw center dot
        gc.setFill(Color.Black)
        gc.fillOval(72, 72, 6, 6)

        // Draw hour text if more than one hour
        if (hours > 0) {
          gc.setFill(Color.Black)
          gc.setFont(new Font("Arial", 14))
          gc.fillText(s"+${hours}h", 65, 95)
        }
      }

      // Bind the clock update to spinner value changes
      hoursSpinner.value.onChange { (_, _, _) => updateSpinners() }
      minutesSpinner.value.onChange { (_, _, _) => updateSpinners() }
      secondsSpinner.value.onChange { (_, _, _) => updateSpinners() }

      // Initial clock update
      updateClock()


      val timeText = new Label {
        text <== Bindings.createStringBinding(
          () => f"${hoursSpinner.value.value}%02d:${minutesSpinner.value.value}%02d:${secondsSpinner.value.value}%02d",
          hoursSpinner.value, minutesSpinner.value, secondsSpinner.value
        )
        style = "-fx-font-size: 18px; -fx-font-weight: bold;"
      }

      // Bind the clock update to spinner value changes
      hoursSpinner.value.onChange { (_, _, _) => updateClock() }
      minutesSpinner.value.onChange { (_, _, _) => updateClock() }
      secondsSpinner.value.onChange { (_, _, _) => updateClock() }

      // Initial clock update
      updateClock()


      val okButton = new Button("OK") {
        style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px;"
        onAction = _ => {
          val totalSeconds = hoursSpinner.value.value * 3600 + minutesSpinner.value.value * 60 + secondsSpinner.value.value
          if (totalSeconds > 0) {
            stepIndex match {
              case Some(index) => macroSteps(index) = Step("Wait", waitSecondsOption = Some(totalSeconds))
              case None => macroSteps.addOne(Step("Wait", waitSecondsOption = Some(totalSeconds)))
            }
          }
          close()
        }
      }

      val cancelButton = new Button("Cancel") {
        style = "-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 14px;"
        onAction = _ => close()
      }

      scene = new Scene {
        content = new VBox(20) {
          alignment = Pos.Center
          padding = Insets(20)
          children = Seq(
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(
                new VBox(5) {
                  alignment = Pos.Center
                  children = Seq(
                    hoursSpinner,
                    new Label("Hours") {
                      style = "-fx-font-size: 12px;"
                    }
                  )
                },
                new VBox(5) {
                  alignment = Pos.Center
                  children = Seq(
                    minutesSpinner,
                    new Label("Minutes") {
                      style = "-fx-font-size: 12px;"
                    }
                  )
                },
                new VBox(5) {
                  alignment = Pos.Center
                  children = Seq(
                    secondsSpinner,
                    new Label("Seconds") {
                      style = "-fx-font-size: 12px;"
                    }
                  )
                }
              )
            },
            clockCanvas,
            timeText,
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(okButton, cancelButton)
            }
          )

        }
      }
    }

    dialog.showAndWait()
  }

  private def showClickSettingsDialog(index: Int): Unit = {
    val step = macroSteps(index)
    val dialog = new Stage {
      title = "Click Settings"
      initModality(javafx.stage.Modality.APPLICATION_MODAL)
      initOwner(stage)

      val buttonGroup = new ToggleGroup()
      val leftButton = new ToggleButton("Left") {
        selected = step.clickSettings.button == "left"
        toggleGroup = buttonGroup
        graphic = new ImageView(new FXImage(getClass.getResourceAsStream("/icons/left-click-icon.png"))) {
          fitWidth = 20
          fitHeight = 20
          preserveRatio = true
        }
      }
      val rightButton = new ToggleButton("Right") {
        selected = step.clickSettings.button == "right"
        toggleGroup = buttonGroup
        graphic = new ImageView(new FXImage(getClass.getResourceAsStream("/icons/right-click-icon.png"))) {
          fitWidth = 20
          fitHeight = 20
          preserveRatio = true
        }
      }

      val labelWidth = 120
      val controlWidth = 150
      val spinnerWidth = 140

      val durationSpinner = new Spinner[Int](1, 1000, step.clickSettings.duration) {
        editable = true
        prefWidth = spinnerWidth
      }

      val clicksSpinner = new Spinner[Int](0, 10, step.clickSettings.clicks) {
        editable = true
        prefWidth = spinnerWidth
      }

      val speedSpinner = new Spinner[Double](0.1, 5.0, step.clickSettings.mouseSpeed, 0.1) {
        editable = true
        prefWidth = spinnerWidth
      }

      scene = new Scene {
        content = new VBox(20) {
          alignment = Pos.Center
          padding = Insets(20)
          children = Seq(
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(
                new Label("Mouse Button:") {
                  prefWidth = labelWidth
                },
                new HBox(10) {
                  alignment = Pos.CenterLeft
                  prefWidth = controlWidth
                  children = Seq(leftButton, rightButton)
                }
              )
            },
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(
                new Label("Duration (ms):") {
                  prefWidth = labelWidth
                },
                new HBox {
                  alignment = Pos.CenterLeft
                  prefWidth = controlWidth
                  children = durationSpinner
                }
              )
            },
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(
                new Label("Number of Clicks:") {
                  prefWidth = labelWidth
                },
                new HBox {
                  alignment = Pos.CenterLeft
                  prefWidth = controlWidth
                  children = clicksSpinner
                }
              )
            },
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(
                new Label("Movement Speed:") {
                  prefWidth = labelWidth
                },
                new HBox {
                  alignment = Pos.CenterLeft
                  prefWidth = controlWidth
                  children = speedSpinner
                }
              )
            },
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(
                new Button("OK") {
                  style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px;"
                  onAction = _ => {
                    val button = if (leftButton.selected.value) "left" else "right"
                    val duration = durationSpinner.value.value
                    val clicks = clicksSpinner.value.value
                    val speed = speedSpinner.value.value
                    val newSettings = ClickSettings(button, duration, clicks, speed)
                    macroSteps(index) = macroSteps(index).copy(clickSettings = newSettings)
                    close()
                  }
                },
                new Button("Cancel") {
                  style = "-fx-background-color: #FF4136; -fx-text-fill: white; -fx-font-size: 14px;"
                  onAction = _ => close()
                }
              )
            }
          )
        }
      }
    }
    dialog.showAndWait()
  }

}