package util

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.{NativeKeyEvent, NativeKeyListener}
import com.sun.jna.platform.win32.{User32, WinUser}
import com.sun.jna.platform.win32.WinDef.{HWND, RECT}
import com.sun.jna.platform.{DesktopWindow, WindowUtils}
import cv.Image.agdImageBuffer
import javafx.animation.Animation
import javafx.beans.binding.Bindings
import javafx.beans.property.{SimpleBooleanProperty, SimpleStringProperty}
import javafx.embed.swing.SwingFXUtils
import javafx.scene.image.Image
import javafx.scene.input.MouseEvent
import scalafx.application.JFXApp3.PrimaryStage
import scalafx.application.{JFXApp3, Platform}
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.canvas.Canvas
import scalafx.scene.control.Alert.AlertType
import scalafx.scene.control._
import scalafx.scene.image.{ImageView, Image => FXImage}
import scalafx.scene.layout._
import scalafx.scene.paint.Color
import scalafx.scene.text.Font
import scalafx.scene.{Node, Scene}
import scalafx.stage.{Modality, Screen, Stage}

import util.Utils.agdBufferedImage

import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.awt.{Dimension, Rectangle, Robot}
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{Duration, LocalDateTime}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import java.util.logging.{Level, Logger}
import javax.swing.filechooser.FileSystemView
import scala.jdk.CollectionConverters._
import scala.util.Try

object Client extends JFXApp3 {

  private var selectedWindow: Option[DesktopWindow] = None

  // Written by the global key listener thread and read by the macro worker thread
  @volatile var stopRequested = false

  // True while a macro is running or scheduled; only modified on the JavaFX thread
  private val busy = new SimpleBooleanProperty(false)

  private var activeScheduler: Option[ScheduledExecutorService] = None

  // How long "Click Visual" keeps looking for the captured image before giving up
  private val VisualSearchTimeoutMs = 5000

  private val OwnWindowTitles = Set("SimpleMacro", "Add Action", "Edit Action", "Select Area to Capture", "Click Position")

  private case class ClickPosition(x: Int, y: Int, windowDimension: Dimension)

  private case class ClickSettings(button: String = "left", duration: Int = 100, clicks: Int = 1, mouseSpeed: Double = 1.0)

  private case class Action(actionType: String, capturedImageOption: Option[BufferedImage] = None, clickPositionOption: Option[ClickPosition] = None, typeTextOption: Option[String] = None, waitSecondsOption: Option[Int] = None, clickSettings: ClickSettings = ClickSettings()) {

    override def toString: String = {
      var s = ""
      if (capturedImageOption.isDefined) s += capturedImageOption.get.toString + " " + clickSettings.toString
      if (clickPositionOption.isDefined) s += clickPositionOption.get.toString + " " + clickSettings.toString
      if (typeTextOption.isDefined) s += typeTextOption.get
      if (waitSecondsOption.isDefined) s += waitSecondsOption.get.toString

      s
    }
  }

  private trait ActionTabContent {
    /** Either the configured action or an error message explaining what is missing. */
    def createAction(): Either[String, Action]
  }

  private def showWarning(titleText: String, message: String): Unit = {
    new Alert(AlertType.Warning) {
      initOwner(stage)
      title = titleText
      headerText = message
    }.showAndWait()
  }

  /** Editable spinners only commit typed text on ENTER; also commit when the editor loses focus. */
  private def commitOnFocusLost[T](spinner: Spinner[T]): Spinner[T] = {
    spinner.delegate.getEditor.focusedProperty.addListener((_, _, focused) => if (!focused) commitSpinner(spinner))
    spinner
  }

  private def commitSpinner[T](spinner: Spinner[T]): Unit = {
    val jfxSpinner = spinner.delegate
    if (jfxSpinner.isEditable && Try(jfxSpinner.commitValue()).isFailure) {
      // Invalid input: restore the last valid value
      jfxSpinner.getEditor.setText(jfxSpinner.getValueFactory.getConverter.toString(jfxSpinner.getValue))
    }
  }

  private def spinnerValue[T](spinner: Spinner[T]): T = {
    commitSpinner(spinner)
    spinner.value.value
  }

  private val actions: ObservableBuffer[Action] = ObservableBuffer.empty[Action]

  private var currentStep = 1

  private def addStopRequestedListener(): Unit = {

    // Disable JNativeHook logging
    Logger.getLogger(classOf[GlobalScreen].getPackage.getName).setLevel(Level.OFF)

    // Register the global key listener
    GlobalScreen.registerNativeHook()
    GlobalScreen.addNativeKeyListener(new NativeKeyListener {
      override def nativeKeyPressed(e: NativeKeyEvent): Unit = {
        if (e.getKeyCode == NativeKeyEvent.VC_ESCAPE && busy.get()) {
          // Set directly: the JavaFX thread may be busy, so runLater could delay the stop
          stopRequested = true
          println("Stop requested by user (global)")
          Platform.runLater(cancelSchedule())
        }
      }

      override def nativeKeyReleased(e: NativeKeyEvent): Unit = {}

      override def nativeKeyTyped(e: NativeKeyEvent): Unit = {}
    })

  }

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
      icons += new Image(getClass.getResourceAsStream("/icons/robot_icon.png"))
    }

    addStopRequestedListener()

    showStep(1)
  }

  override def stopApp(): Unit = {
    stopRequested = true
    activeScheduler.foreach(_.shutdownNow())
    // The native hook thread would otherwise keep the JVM alive after the window is closed
    Try(GlobalScreen.unregisterNativeHook())
  }


  private def createStep1Scene(): Scene = {

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

    var searchTerm = ""

    def getWindowItems: Array[WindowItem] = {

      WindowUtils.getAllWindows(true).asScala
        .filter(w => w.getTitle.nonEmpty && !w.getTitle.contains("Task Manager") && !OwnWindowTitles.contains(w.getTitle))
        .filter(_.getTitle.toLowerCase.contains(searchTerm))
        .flatMap(w => Option(WindowUtils.getWindowIcon(w.getHWND)).orElse(Try(getIcon(w.getFilePath)).toOption).map(WindowItem(w, _)))
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
              onKeyReleased = (_: javafx.scene.input.KeyEvent) => {
                searchTerm = text.value.toLowerCase
                filteredItems.clear()
                filteredItems.addAll(getWindowItems)
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
                val selectedItem = listView.selectionModel().getSelectedItem
                if (selectedItem != null) {
                  selectedWindow = Some(selectedItem.window)
                  refreshTimeline.stop()
                  showStep(2)
                } else {
                  showWarning("No window selected", "Please select the window the macro should run in.")
                }
              }
            }
          )
        }
      }
    }
  }



  //---------------------------------------------------------------------


  /** Returns the click setting controls and a function reading the configured settings. */
  private def createCommonClickLayout(existingAction: Option[Action] = None): (Seq[Node], () => ClickSettings) = {
    val buttonGroup = new ToggleGroup()
    // Prevent deselecting the active button, which would leave no button selected
    buttonGroup.selectedToggle.onChange { (_, oldToggle, newToggle) =>
      if (newToggle == null && oldToggle != null) buttonGroup.selectToggle(oldToggle)
    }
    val leftButton = new ToggleButton("Left") {
      id = "leftMouse"
      selected = existingAction.forall(_.clickSettings.button == "left")
      toggleGroup = buttonGroup
      graphic = new ImageView(new FXImage(getClass.getResourceAsStream("/icons/left-click-icon.png"))) {
        fitWidth = 20
        fitHeight = 20
        preserveRatio = true
      }
    }
    val rightButton = new ToggleButton("Right") {
      id = "rightMouse"
      selected = existingAction.exists(_.clickSettings.button == "right")
      toggleGroup = buttonGroup
      graphic = new ImageView(new FXImage(getClass.getResourceAsStream("/icons/right-click-icon.png"))) {
        fitWidth = 20
        fitHeight = 20
        preserveRatio = true
      }
    }

    val spinnerWidth = 140

    val defaults = existingAction.map(_.clickSettings).getOrElse(ClickSettings())

    val durationSpinner = commitOnFocusLost(new Spinner[Int](0, 10000, defaults.duration, 50) {
      editable = true
      prefWidth = spinnerWidth
    })

    val clicksSpinner = commitOnFocusLost(new Spinner[Int](0, 10, defaults.clicks) {
      editable = true
      prefWidth = spinnerWidth
    })

    val speedSpinner = commitOnFocusLost(new Spinner[Double](0.1, 5.0, defaults.mouseSpeed, 0.1) {
      editable = true
      prefWidth = spinnerWidth
    })

    def readSettings(): ClickSettings = ClickSettings(
      button = if (leftButton.selected.value) "left" else "right",
      duration = spinnerValue(durationSpinner),
      clicks = spinnerValue(clicksSpinner),
      mouseSpeed = spinnerValue(speedSpinner)
    )

    val labelWidth = 120
    val controlWidth = 150

    (Seq(
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
          new Label("Delay after (ms):") {
            prefWidth = labelWidth
          },
          new HBox {
            alignment = Pos.CenterLeft
            prefWidth = controlWidth
            children = Seq(durationSpinner)
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
            children = Seq(clicksSpinner)
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
            children = Seq(speedSpinner)
          }
        )
      }
    ), () => readSettings())
  }


  private def captureMousePositionContent(existingAction: Option[Action] = None): Node = {
    var clickPositionOption: Option[ClickPosition] = existingAction.flatMap(_.clickPositionOption)

    val positionLabel = new Label() {
      style = "-fx-font-size: 14px; -fx-text-fill: #4CAF50;"
    }

    def updatePositionLabel(): Unit = {
      positionLabel.text = clickPositionOption.map(p => s"Recorded position: (${p.x}, ${p.y})").getOrElse("No position recorded yet")
    }

    updatePositionLabel()

    val captureButton = new Button(if (clickPositionOption.isDefined) "Recapture Position" else "Capture Position") {
      style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px;"
      onAction = _ => {
        recordMousePosition.foreach { mousePosition =>
          clickPositionOption = Some(mousePosition)
          updatePositionLabel()
          text = "Recapture Position"
        }
      }
    }

    val positionBox = new VBox(10) {
      alignment = Pos.Center
      children = Seq(captureButton, positionLabel)
      minHeight = 130 // Set a minimum height to match the image capture area
    }

    val (clickLayout, readSettings) = createCommonClickLayout(existingAction)

    new VBox(20) {
      alignment = Pos.BottomCenter
      padding = Insets(20)
      children = Seq(positionBox) ++ clickLayout

      userData = new ActionTabContent {
        override def createAction(): Either[String, Action] = clickPositionOption match {
          case Some(position) => Right(Action("Click Position", clickPositionOption = Some(position), clickSettings = readSettings()))
          case None => Left("Please capture a position to click first.")
        }
      }
    }
  }

  private def createClickVisualContent(existingAction: Option[Action] = None): Node = {
    var imageOption: Option[BufferedImage] = existingAction.flatMap(_.capturedImageOption)

    val imageView = new ImageView {
      fitWidth = 200
      fitHeight = 100
      preserveRatio = true
      image = imageOption.map(img => new FXImage(SwingFXUtils.toFXImage(img, null))).orNull
      visible <== image.isNotNull
    }

    val captureButton = new Button(if (imageOption.isDefined) "Recapture Image" else "Capture Image") {
      style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px;"
      onAction = _ => {
        captureImage().foreach { bufferedImage =>
          imageOption = Some(bufferedImage)
          imageView.image = new FXImage(SwingFXUtils.toFXImage(bufferedImage, null))
          text = "Recapture Image"
        }
      }
    }

    val imageBox = new VBox(10) {
      alignment = Pos.Center
      children = Seq(imageView, captureButton)
      minHeight = 130
    }

    val (clickLayout, readSettings) = createCommonClickLayout(existingAction)

    new VBox(20) {
      alignment = Pos.BottomCenter
      padding = Insets(20)
      children = Seq(imageBox) ++ clickLayout

      userData = new ActionTabContent {
        override def createAction(): Either[String, Action] = imageOption match {
          case Some(img) => Right(Action("Click Visual", capturedImageOption = Some(img), clickSettings = readSettings()))
          case None => Left("Please capture the image to click on first.")
        }
      }
    }
  }

  private def createWaitContent(existingAction: Option[Action] = None): Node = {

    val initialSeconds = existingAction.flatMap(_.waitSecondsOption).getOrElse(3)

    val hoursSpinner = commitOnFocusLost(new Spinner[Int](0, 23, initialSeconds / 3600) {
      editable = true
      prefWidth = 70
    })
    val minutesSpinner = commitOnFocusLost(new Spinner[Int](0, 59, (initialSeconds % 3600) / 60) {
      editable = true
      prefWidth = 70
    })
    val secondsSpinner = commitOnFocusLost(new Spinner[Int](0, 59, initialSeconds % 60) {
      editable = true
      prefWidth = 70
    })

    val clockCanvas = new Canvas(150, 150)
    val gc = clockCanvas.graphicsContext2D

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


    new VBox(20) {
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
        timeText
      )
      userData = new ActionTabContent {
        override def createAction(): Either[String, Action] = {
          val waitSeconds = spinnerValue(hoursSpinner) * 3600 + spinnerValue(minutesSpinner) * 60 + spinnerValue(secondsSpinner)
          Right(Action("Wait", waitSecondsOption = Some(waitSeconds)))
        }
      }

    }
  }


  private def createTypeTextContent(existingAction: Option[Action] = None): Node = {
    val textField = new TextField {
      id = "textField"
      text = existingAction.flatMap(_.typeTextOption).getOrElse("")
    }

    new VBox(10) {
      padding = Insets(10)
      children = Seq(
        new Label("Text to type:"),
        textField,
        new Label("Letters, digits and spaces are typed key by key; other characters are pasted via the clipboard.") {
          wrapText = true
          style = "-fx-font-size: 11px; -fx-text-fill: gray;"
        }
      )
      userData = new ActionTabContent {
        override def createAction(): Either[String, Action] =
          if (textField.text.value.isEmpty) Left("Please enter the text to type.")
          else Right(Action("Type Text", typeTextOption = Some(textField.text.value)))
      }
    }
  }


  def createActionButton(actionName: String, iconName: String, contentCreator: () => Node): Button = {
    new Button {
      val icon = new ImageView(new FXImage(new Image(getClass.getResourceAsStream(s"/icons/$iconName")))) {
        fitHeight = 30
        fitWidth = 30
        preserveRatio = true
      }

      val label = new Label(actionName) {
        style = "-fx-font-size: 12px;"
      }

      graphic = new VBox(5) {
        alignment = Pos.Center
        children = Seq(icon, label)
      }

      style = "-fx-min-width: 100px; -fx-min-height: 80px; -fx-content-display: top;"
      userData = contentCreator
      focusTraversable = false
    }
  }


  private def showAddActionWindow(insertIndex: Option[Int] = None): Unit = {
    val actionSelectionStage = new Stage() {
      title = "Add Action"
      width = 465
      height = 570
      icons += new Image(getClass.getResourceAsStream("/icons/robot_icon.png"))
      resizable = false
      initModality(Modality.None)
      initOwner(stage)
    }

    val contentArea = new StackPane()

    val clickPositionButton = createActionButton("Click Position", "mouse_icon.png", () => captureMousePositionContent())
    val clickVisualButton = createActionButton("Click Visual", "click_icon.png", () => createClickVisualContent())
    val waitButton = createActionButton("Wait", "time-icon.png", () => createWaitContent(None))
    val typeTextButton = createActionButton("Type Text", "text_icon.png", () => createTypeTextContent())

    val buttonBar = new HBox(10) {
      alignment = Pos.Center
      children = Seq(clickPositionButton, clickVisualButton, waitButton, typeTextButton)

    }

    def setContent(content: Node): Unit = {
      contentArea.children.clear()
      contentArea.children.add(content)
    }

    def markSelectedButton(selectedButton: Button): Unit = {
      List(clickPositionButton, clickVisualButton, waitButton, typeTextButton).foreach { button =>
        if (button == selectedButton) {
          button.style = "-fx-min-width: 100px; -fx-min-height: 80px; -fx-content-display: top; -fx-background-color: #4CAF50;"
        } else {
          button.style = "-fx-min-width: 100px; -fx-min-height: 80px; -fx-content-display: top;"
        }
      }
    }

    val initialContent = new VBox(20) {
      alignment = Pos.Center
      children = Seq(
        new HBox(20) {
          alignment = Pos.Center
          children = Seq(
            new VBox(10) {
              alignment = Pos.TopCenter
              children = Seq(
                new Label("↓") {
                  style = "-fx-font-size: 24px;"
                },
                new Label("Click Position") {
                  style = "-fx-font-weight: bold;"
                },
                new Label("Click a specific mouse position") {
                  style = "-fx-font-size: 12px; -fx-text-alignment: center;"
                  wrapText = true
                  maxWidth = 100
                }
              )
            },
            new VBox(10) {
              alignment = Pos.TopCenter
              children = Seq(
                new Label("↓") {
                  style = "-fx-font-size: 24px;"
                },
                new Label("Click Visual") {
                  style = "-fx-font-weight: bold;"
                },
                new Label("Click based on a visual element") {
                  style = "-fx-font-size: 12px; -fx-text-alignment: center;"
                  wrapText = true
                  maxWidth = 100
                }
              )
            },
            new VBox(10) {
              alignment = Pos.TopCenter
              children = Seq(
                new Label("↓") {
                  style = "-fx-font-size: 24px;"
                },
                new Label("Wait") {
                  style = "-fx-font-weight: bold;"
                },
                new Label("Add a waiting period") {
                  style = "-fx-font-size: 12px; -fx-text-alignment: center;"
                  wrapText = true
                  maxWidth = 100
                }
              )
            },
            new VBox(10) {
              alignment = Pos.TopCenter
              children = Seq(
                new Label("↓") {
                  style = "-fx-font-size: 24px;"
                },
                new Label("Type Text") {
                  style = "-fx-font-weight: bold;"
                },
                new Label("Enter text at the current cursor position") {
                  style = "-fx-font-size: 12px; -fx-text-alignment: center;"
                  wrapText = true
                  maxWidth = 100
                }
              )
            }
          )
        }
      )
    }

    setContent(initialContent)

    List(clickPositionButton, clickVisualButton, waitButton, typeTextButton).foreach { button =>
      button.onAction = _ => {
        setContent(button.userData.asInstanceOf[() => Node]())
        markSelectedButton(button)
      }
    }

    actionSelectionStage.scene = new Scene {
      root = new VBox(10) {
        padding = Insets(10)
        children = Seq(
          buttonBar,
          contentArea,
          new Region {
            vgrow = Priority.Always
          },
          new HBox {
            spacing = 10
            alignment = Pos.BottomRight
            children = Seq(
              new Button("Add Action") {
                style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
                onAction = _ => {
                  contentArea.children.get(0).getUserData match {
                    case actionContent: ActionTabContent =>
                      actionContent.createAction() match {
                        case Right(action) =>
                          insertIndex match {
                            case Some(index) => actions.insert(index, action)
                            case None => actions += action
                          }
                          actionSelectionStage.close()
                        case Left(message) => showWarning("Incomplete action", message)
                      }
                    case _ =>
                      showWarning("No action selected", "Please choose an action type first.")
                  }
                }
              },
              new Button("Cancel") {
                style = "-fx-background-color: #FF4136; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
                onAction = _ => actionSelectionStage.close()
              }
            )
          }
        )
      }
    }

    actionSelectionStage.showAndWait()
  }

  private def showEditActionWindow(action: Action, index: Int): Unit = {
    val editActionStage = new Stage() {
      title = "Edit Action"
      width = 440
      height = 500
      icons += new Image(getClass.getResourceAsStream("/icons/robot_icon.png"))
      resizable = false
      initModality(Modality.None)
      initOwner(stage)
    }

    // Create only the relevant content based on the action type
    val relevantContent = action.actionType match {
      case "Click Position" =>
        captureMousePositionContent(Some(action))
      case "Click Visual" =>
        createClickVisualContent(Some(action))
      case "Wait" =>
        createWaitContent(Some(action))
      case "Type Text" =>
        createTypeTextContent(Some(action))
    }

    editActionStage.scene = new Scene {
      root = new BorderPane {
        center = relevantContent
        bottom = new HBox {
          spacing = 10
          alignment = Pos.BottomRight
          padding = Insets(10)
          children = Seq(
            new Button("Change Action") {
              style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
              onAction = _ => {
                relevantContent.userData.asInstanceOf[ActionTabContent].createAction() match {
                  case Right(updatedAction) =>
                    actions(index) = updatedAction
                    editActionStage.close()
                  case Left(message) => showWarning("Incomplete action", message)
                }
              }
            },
            new Button("Cancel") {
              style = "-fx-background-color: #FF4136; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
              onAction = _ => editActionStage.close()
            }
          )
        }
      }
    }

    editActionStage.showAndWait()
  }


  //---------------------------------------------------------------------


  private def createActionRow(action: Action, cell: javafx.scene.control.ListCell[Action]): javafx.scene.Node = {
    val removeButton = new Button("X") {
      style = "-fx-background-color: #FF4136; -fx-text-fill: white; -fx-font-size: 14px;"
      onAction = _ => {
        val index = cell.getIndex
        if (index >= 0 && index < actions.size) actions.remove(index)
      }
    }

    val settingsButton = new Button("⚙") {
      style = "-fx-background-color: #4682B4; -fx-text-fill: white; -fx-font-size: 14px;"
      onAction = _ => {
        val index = cell.getIndex
        if (index >= 0 && index < actions.size) showEditActionWindow(actions(index), index)
      }
    }

    def detailLabel(text: String): Label = new Label(text) {
      style = "-fx-font-size: 14px;"
      textOverrun = scalafx.scene.control.OverrunStyle.Ellipsis
    }

    val details: Option[Node] =
      action.capturedImageOption.map[Node] { img =>
        new ImageView(new FXImage(SwingFXUtils.toFXImage(img, null))) {
          preserveRatio = true
          fitHeight = 30
          fitWidth = 100
        }
      }
        .orElse(action.clickPositionOption.map(pos => detailLabel(s"(${pos.x}, ${pos.y})")))
        .orElse(action.typeTextOption.map(text => detailLabel(s"\"$text\"")))
        .orElse(action.waitSecondsOption.map(seconds => detailLabel(Utils.formatDuration(seconds))))

    new HBox(5) {
      alignment = Pos.CenterLeft
      children = Seq(
        new Label(s"${cell.getIndex + 1}.") {
          style = "-fx-font-size: 14px; -fx-font-weight: bold;"
          minWidth = 30
        },
        new Label(action.actionType) {
          style = "-fx-font-size: 14px;"
          minWidth = Region.USE_PREF_SIZE
        }
      ) ++ details ++ Seq(
        new Region() {
          hgrow = Priority.Always
        },
        settingsButton,
        removeButton
      )
    }
  }

  private def createStep2Scene(): Scene = {
    new Scene(700, 500) {
      root = new BorderPane {
        top = new HBox {
          alignment = Pos.CenterLeft
          padding = Insets(10, 0, 10, 15)
          children = Seq(
            new Label("Step 2: Define Actions") {
              style = "-fx-font-size: 28px; -fx-font-weight: bold;"
            }
          )
        }

        val listView: ListView[Action] = new ListView[Action](actions) {
          prefWidth = 350
          prefHeight = 400
          // updateItem is also called when only the index changes (e.g. after removing an action),
          // so the row number and the button handlers never refer to a stale position
          cellFactory = (_: ListView[Action]) => new ListCell[Action](new javafx.scene.control.ListCell[Action] {
            setPrefHeight(40)

            override def updateItem(action: Action, empty: Boolean): Unit = {
              super.updateItem(action, empty)
              setText(null)
              setGraphic(if (empty || action == null) null else createActionRow(action, this))
            }
          })

          var previousSelectedIndex = -1
          onMouseClicked = (event: MouseEvent) => if (event.getClickCount == 1) {
            val index = selectionModel().getSelectedIndex
            if (previousSelectedIndex != index) {
              this.selectionModel().select(index)
              previousSelectedIndex = index
            } else {
              this.selectionModel().select(-1)
              previousSelectedIndex = -1
            }
          }


          selectionModel().setSelectionMode(SelectionMode.Single)
        }


        val addActionButton = new Button("Add Action") {
          style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px;"
          graphic = new ImageView(new FXImage(getClass.getResourceAsStream("/icons/add_icon.png"))) {
            fitHeight = 24
            fitWidth = 24
            preserveRatio = true
          }
          onAction = _ => {
            val selectedIndex = listView.selectionModel().getSelectedIndex
            val insertIndex = if (selectedIndex >= 0) Some(selectedIndex + 1) else None
            showAddActionWindow(insertIndex)
          }
          maxWidth = Double.MaxValue
        }

        listView.selectionModel().selectedItemProperty().addListener((_, _, newValue) => {
          if (newValue != null) {
            addActionButton.text = "Add Action after Selection"
          } else {
            addActionButton.text = "Add Action"
          }
        })

        center = new VBox(20) {
          alignment = Pos.Center
          padding = Insets(0, 15, 15, 15)
          children = Seq(
            listView,
            addActionButton
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
              onAction = _ => if (actions.nonEmpty) showStep(3) else {
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

    val currentActionLabel = new Label(if (busy.get()) "Macro is running..." else "Ready to execute") {
      style = "-fx-font-size: 16px;"
      wrapText = true
    }

    val executeNowButton = new Button("Execute Now")
    val scheduleButton = new Button("Schedule")

    val selectedMode = new SimpleStringProperty("ExecuteNow")

    def updateButtonStyles(): Unit = {
      executeNowButton.style = {
        if (selectedMode.get() == "ExecuteNow") "-fx-background-color: #4682B4; -fx-text-fill: white;" else "-fx-background-color: #D3D3D3;"
      } + "-fx-font-size: 14px; -fx-padding: 8 15;"
      scheduleButton.style = {
        if (selectedMode.get() == "Schedule") "-fx-background-color: #4682B4; -fx-text-fill: white;" else "-fx-background-color: #D3D3D3;"
      } + "-fx-font-size: 14px; -fx-padding: 8 15;"
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

    def timeSpinner(max: Int, initial: Int) = commitOnFocusLost(new Spinner[Int](0, max, initial) {
      editable = true
      prefWidth = 65
    })

    val now = LocalDateTime.now()
    val hourSpinner = timeSpinner(23, now.getHour)
    val minuteSpinner = timeSpinner(59, now.getMinute)
    val secondSpinner = timeSpinner(59, 0)

    val repeatCountSpinner = commitOnFocusLost(new Spinner[Int](1, 1000, 1) {
      editable = true
      prefWidth = 80
    })
    val repeatIntervalSpinner = commitOnFocusLost(new Spinner[Int](1, 1000, 1) {
      editable = true
      prefWidth = 80
    })
    val repeatUnitComboBox = new ComboBox[String](ObservableBuffer("Seconds", "Minutes", "Hours"))
    repeatUnitComboBox.value = "Minutes"

    val executeButton = new Button("Execute") {
      style = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 16px; -fx-padding: 10 20;"
      disable <== busy
      onAction = _ => if (isSelectedWindowOpen) {
        stopRequested = false
        val repeatCount = spinnerValue(repeatCountSpinner)
        // Snapshot the actions so later edits do not affect a running or scheduled macro
        val macroActions = actions.toList
        if (selectedMode.get() == "ExecuteNow") {
          executeMacroNow(currentActionLabel, macroActions, repeatCount)
        } else {
          val scheduledTime = LocalDateTime.now()
            .withHour(spinnerValue(hourSpinner))
            .withMinute(spinnerValue(minuteSpinner))
            .withSecond(spinnerValue(secondSpinner))
            .withNano(0)
          val interval = repeatUnitComboBox.value.value match {
            case "Seconds" => Duration.ofSeconds(spinnerValue(repeatIntervalSpinner))
            case "Minutes" => Duration.ofMinutes(spinnerValue(repeatIntervalSpinner))
            case _ => Duration.ofHours(spinnerValue(repeatIntervalSpinner))
          }
          scheduleMacro(currentActionLabel, macroActions, scheduledTime, repeatCount, interval)
        }
      }
    }

    val stopButton = new Button("Stop") {
      style = "-fx-background-color: #FF4136; -fx-text-fill: white; -fx-font-size: 16px; -fx-padding: 10 20;"
      disable <== busy.not()
      onAction = _ => {
        stopRequested = true
        if (cancelSchedule()) currentActionLabel.text = "Schedule cancelled"
      }
    }

    new Scene(700, 500) {
      root = new BorderPane {
        top = new VBox(10) {
          alignment = Pos.CenterLeft
          padding = Insets(20)
          children = Seq(
            new Label("Step 3: Execute Actions") {
              style = "-fx-font-size: 24px; -fx-font-weight: bold;"
            },
            new Label("Press ESC at any time to stop the execution.") {
              style = "-fx-font-size: 16px; -fx-text-fill: #4CAF50;"
            },
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
                    new Label("Start at:") {
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
            new HBox(10) {
              alignment = Pos.Center
              children = Seq(executeButton, stopButton)
            },
            new VBox(10) {
              alignment = Pos.Center
              children = Seq(currentActionLabel)
            }
          )
        }
        bottom = new HBox(10) {
          alignment = Pos.CenterLeft
          padding = Insets(20)
          children = Seq(
            new Button("Previous") {
              style = "-fx-background-color: #4682B4; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
              disable <== busy
              onAction = _ => showStep(2)
            }
          )
        }
      }
    }
  }

  /** Runs the macro `runs` times, starting at `startTime` (or tomorrow if that time has passed) and then every `interval`. */
  private def scheduleMacro(currentActionLabel: Label, macroActions: List[Action], startTime: LocalDateTime, runs: Int, interval: Duration): Unit = {
    val now = LocalDateTime.now()
    val firstRun = if (startTime.isBefore(now)) startTime.plusDays(1) else startTime
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    cancelSchedule()
    val scheduler = Executors.newSingleThreadScheduledExecutor((r: Runnable) => {
      val thread = new Thread(r, "macro-scheduler")
      thread.setDaemon(true)
      thread
    })
    activeScheduler = Some(scheduler)
    busy.set(true)

    val executionCount = new AtomicInteger(0)

    // A single-threaded executor never runs two macro executions at the same time; if a run takes
    // longer than the interval, the next run starts right after it.
    scheduler.scheduleAtFixedRate(() => {
      val run = executionCount.incrementAndGet()
      if (stopRequested || run > runs) {
        scheduler.shutdown()
      } else {
        updateLabel(currentActionLabel, s"Scheduled run $run/$runs")
        val completed = runMacro(currentActionLabel, macroActions, 1, s"Run $run/$runs")
        if (!completed || run == runs) {
          scheduler.shutdown()
          Platform.runLater {
            if (activeScheduler.contains(scheduler)) {
              activeScheduler = None
              busy.set(false)
            }
          }
        } else {
          val nextRun = firstRun.plus(interval.multipliedBy(run.toLong))
          val next = if (nextRun.isAfter(LocalDateTime.now())) nextRun.format(timeFormat) else "now"
          updateLabel(currentActionLabel, s"Run $run/$runs completed. Next run: $next")
        }
      }
    }, Duration.between(now, firstRun).toMillis, interval.toMillis, TimeUnit.MILLISECONDS)

    currentActionLabel.text = s"Scheduled to start at ${firstRun.format(timeFormat)}, running $runs time${if (runs > 1) "s" else ""}"
  }

  /** Cancels a pending schedule. Returns true if there was one. Must be called on the JavaFX thread. */
  private def cancelSchedule(): Boolean = activeScheduler match {
    case Some(scheduler) =>
      scheduler.shutdownNow()
      activeScheduler = None
      busy.set(false)
      true
    case None => false
  }

  private def isSelectedWindowOpen: Boolean = {
    val open = selectedWindow.exists(window => User32.INSTANCE.IsWindow(window.getHWND))
    if (!open) {
      println("Window closed")
      Platform.runLater {
        new Alert(AlertType.Warning) {
          initOwner(stage)
          title = "Window closed"
          headerText = "The selected window was closed."
          contentText = "Please select a window"
        }.showAndWait()
        showStep(1)
      }
    }
    open
  }

  private def updateLabel(label: Label, text: String): Unit = Platform.runLater {
    label.text = text
  }

  private def executeMacroNow(currentActionLabel: Label, macroActions: List[Action], loopCount: Int): Unit = {
    busy.set(true)
    val worker = new Thread(() => {
      try runMacro(currentActionLabel, macroActions, loopCount)
      finally Platform.runLater(busy.set(false))
    }, "macro-worker")
    worker.setDaemon(true)
    worker.start()
  }

  /** Sleeps in small steps so that a stop request is handled promptly. */
  private def sleepUnlessStopped(millis: Long): Unit = {
    val end = System.currentTimeMillis() + millis
    while (!stopRequested && System.currentTimeMillis() < end) {
      Thread.sleep(Math.min(100L, end - System.currentTimeMillis()).max(1L))
    }
  }

  /**
   * Executes all actions `loopCount` times on the calling (non-JavaFX) thread.
   * Returns true if every action completed, false if the macro was stopped or failed.
   */
  private def runMacro(currentActionLabel: Label, macroActions: List[Action], loopCount: Int, prefix: String = ""): Boolean = {
    val robot = new Robot()
    val totalActions = macroActions.size

    def fail(titleText: String, message: String): Boolean = {
      stopRequested = true
      Platform.runLater {
        new Alert(AlertType.Error) {
          initOwner(stage)
          title = titleText
          headerText = message
        }.showAndWait()
      }
      false
    }

    def performClick(x: Int, y: Int, action: Action): Unit = {
      // Move the mouse in a human-like manner
      Mouse.moveHumanLike(robot, x, y, action.clickSettings.mouseSpeed)

      val button = action.clickSettings.button match {
        case "right" => InputEvent.BUTTON3_DOWN_MASK
        case _ => InputEvent.BUTTON1_DOWN_MASK
      }

      val clickCount = action.clickSettings.clicks
      for (_ <- 1 to clickCount) {
        robot.mousePress(button)
        robot.mouseRelease(button)
        if (clickCount > 1) Thread.sleep(50) // Small delay between clicks for double-click
      }

      sleepUnlessStopped(action.clickSettings.duration)
    }

    /** Screen area of the window's client area (without title bar and borders). */
    def clientArea(hwnd: HWND): Rectangle = {
      val rect = new RECT()
      User32.INSTANCE.GetWindowRect(hwnd, rect)

      val clientRect = new RECT()
      User32.INSTANCE.GetClientRect(hwnd, clientRect)

      val borderWidth = (rect.right - rect.left - clientRect.right) / 2
      val titleBarHeight = rect.bottom - rect.top - clientRect.bottom - borderWidth

      new Rectangle(rect.left + borderWidth, rect.top + titleBarHeight, clientRect.right, clientRect.bottom)
    }

    def findImage(hwnd: HWND, image: BufferedImage): Option[(Int, Int)] = {
      val deadline = System.currentTimeMillis() + VisualSearchTimeoutMs
      var found: Option[(Int, Int)] = None
      // The element might not be visible yet (e.g. a page is still loading), so keep looking for a while
      while (found.isEmpty && !stopRequested && System.currentTimeMillis() < deadline) {
        val area = clientArea(hwnd)
        if (area.width > 0 && area.height > 0) {
          found = robot.createScreenCapture(area).findBestMatch(image).map { m =>
            (area.x + m.rect.x + m.rect.width / 2, area.y + m.rect.y + m.rect.height / 2)
          }
        }
        if (found.isEmpty) sleepUnlessStopped(250)
      }
      found
    }

    def executeAction(action: Action): Boolean = action.actionType match {
      case "Click Position" | "Click Visual" if !selectedWindow.exists(w => User32.INSTANCE.IsWindow(w.getHWND)) =>
        fail("Window closed", "The selected window was closed during execution.")

      case "Click Position" =>
        for (clickPosition <- action.clickPositionOption; window <- selectedWindow) {
          val hwnd = window.getHWND

          // Restore the window size the position was recorded with
          val currentBounds = WindowUtils.getWindowLocationAndSize(hwnd)
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
          windowToFront(hwnd)

          val windowBounds = WindowUtils.getWindowLocationAndSize(hwnd)
          performClick(windowBounds.x + clickPosition.x, windowBounds.y + clickPosition.y, action)
        }
        true

      case "Click Visual" =>
        (for (capturedImage <- action.capturedImageOption; window <- selectedWindow) yield {
          val hwnd = window.getHWND
          windowToFront(hwnd)

          findImage(hwnd, capturedImage) match {
            case Some((x, y)) =>
              performClick(x, y, action)
              true
            case None if stopRequested => false
            case None =>
              println("Unable to find the captured image in the window")
              fail("Image Not Found", s"The captured image was not found in the window within ${VisualSearchTimeoutMs / 1000} seconds. The macro was stopped.")
          }
        }).getOrElse(true)

      case "Type Text" =>
        action.typeTextOption.foreach(text => Keyboard.typeText(robot, text, () => stopRequested))
        true

      case "Wait" =>
        action.waitSecondsOption.foreach(seconds => sleepUnlessStopped(seconds * 1000L))
        true

      case _ => true
    }

    val labelPrefix = if (prefix.nonEmpty) s"$prefix - " else ""
    var completed = true
    try {
      var loop = 1
      while (completed && loop <= loopCount) {
        val remaining = macroActions.zipWithIndex.iterator
        while (completed && remaining.hasNext) {
          val (action, index) = remaining.next()
          if (stopRequested) completed = false
          else {
            updateLabel(currentActionLabel, s"${labelPrefix}Loop $loop/$loopCount - Executing action ${index + 1} of $totalActions: ${action.actionType}")
            completed = executeAction(action) && !stopRequested
          }
        }
        if (completed && loop < loopCount) sleepUnlessStopped(500) // Short pause between loops
        loop += 1
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        completed = fail("Execution failed", s"An error occurred while executing the macro: ${e.getMessage}")
    }

    updateLabel(currentActionLabel, if (completed) s"${labelPrefix}Macro execution completed" else s"${labelPrefix}Macro execution stopped")
    completed
  }

  //--------------------------------------------------------------------------


  def windowToFront(hwnd: HWND): Unit = {
    // Only restore minimized windows; SW_SHOWDEFAULT would un-maximize maximized ones
    val placement = new WinUser.WINDOWPLACEMENT()
    User32.INSTANCE.GetWindowPlacement(hwnd, placement)
    if (placement.showCmd == WinUser.SW_SHOWMINIMIZED) User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE)
    User32.INSTANCE.SetForegroundWindow(hwnd)
    Thread.sleep(200)
  }

  private def captureImage(): Option[BufferedImage] = if (isSelectedWindowOpen) {
    var imageOption: Option[BufferedImage] = None

    selectedWindow.foreach { window =>
      val hwnd = window.getHWND

      windowToFront(hwnd)

      val screenBounds = Screen.primary.bounds

      // Take the screenshot before the overlay is shown, so the captured image contains neither
      // the overlay tint nor the selection rectangle (both would lower the match confidence later)
      val screenshot = new Robot().createScreenCapture(
        new Rectangle(screenBounds.minX.toInt, screenBounds.minY.toInt, screenBounds.width.toInt, screenBounds.height.toInt))

      // Create a transparent overlay stage
      val overlayStage: Stage = new Stage {
        title = "Select Area to Capture"
        fullScreen = true
        fullScreenExitHint = "Drag to select the area to capture (max. 400x400). Press ESC to cancel."
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
            val x = Math.min(startX, endX).toInt.max(0)
            val y = Math.min(startY, endY).toInt.max(0)
            val width = Math.min(Math.abs(endX - startX).toInt, 400).min(screenshot.getWidth - x)
            val height = Math.min(Math.abs(endY - startY).toInt, 400).min(screenshot.getHeight - y)

            if (width > 0 && height > 0) {
              imageOption = Some(screenshot.crop(x, y, width, height))
            } else {
              println("No area selected to capture")
              Platform.runLater {
                new Alert(AlertType.Error) {
                  title = "No Area Selected"
                  headerText = "Please select an area to capture."
                }.showAndWait()
              }
            }
          }
        }
      }

      overlayStage.showAndWait()
    }

    imageOption

  } else None


  private def recordMousePosition: Option[ClickPosition] = if (isSelectedWindowOpen) {
    var clickPositionOption: Option[ClickPosition] = None

    selectedWindow.foreach { window =>
      val hwnd = window.getHWND

      windowToFront(hwnd)

      // Create a transparent overlay stage
      val screenBounds = Screen.primary.bounds
      val overlayStage: Stage = new Stage {
        title = "Click Position"
        fullScreen = true
        fullScreenExitHint = "Click the position to record. Press ESC to cancel."
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

            clickPositionOption = Some(clickPosition)

            stage.toFront()
            close()
          }
        }
      }

      overlayStage.showAndWait()
    }

    clickPositionOption
  } else None

}