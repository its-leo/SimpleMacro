package ui

import engine.{Keyboard, TargetWindow}
import javafx.scene.input.{KeyEvent => JfxKeyEvent}
import model.MacroAction._
import model._
import scalafx.Includes._
import scalafx.beans.binding.Bindings
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Node
import scalafx.scene.canvas.Canvas
import scalafx.scene.control._
import scalafx.scene.image.ImageView
import scalafx.scene.layout.{HBox, VBox}
import scalafx.scene.paint.Color
import scalafx.scene.text.Font
import scalafx.stage.Window
import ui.UiKit._

import java.awt.image.BufferedImage

/** Captures positions and images in the selected window for the editors. */
class EditorContext(state: AppState, owner: () => Window) {

  private def withWindow[T](capture: TargetWindow => Option[T]): Option[T] = state.selectedWindow.filter(_.isOpen) match {
    case Some(window) =>
      val result = capture(window)
      Option(owner()).foreach(_.requestFocus())
      result
    case None =>
      showWarning(owner(), "Window closed", "The selected window is no longer open. Please select a window in step 1.")
      None
  }

  def capturePoint(hint: String): Option[WindowPoint] = withWindow { window =>
    CaptureOverlay.pickPoint(state.desktop, window, hint).map { p =>
      val bounds = window.bounds
      WindowPoint(Point(p.x - bounds.x, p.y - bounds.y), Size(bounds.width, bounds.height))
    }
  }

  def captureImage(hint: String): Option[BufferedImage] = withWindow { window =>
    CaptureOverlay.pickImage(state.desktop, window, hint)
  }

  def captureDrag(hint: String): Option[(Point, Point, Size)] = withWindow { window =>
    CaptureOverlay.pickDrag(state.desktop, window, hint).map { case (from, to) =>
      val bounds = window.bounds
      (Point(from.x - bounds.x, from.y - bounds.y), Point(to.x - bounds.x, to.y - bounds.y), Size(bounds.width, bounds.height))
    }
  }
}

trait ActionEditor {
  def node: Node

  /** The configured action, or a message explaining what is missing. */
  def result(): Either[String, MacroAction]
}

case class ActionKind(name: String, iconFile: String, description: String,
                      matches: MacroAction => Boolean,
                      createEditor: (EditorContext, Option[MacroAction]) => ActionEditor)

object ActionEditors {

  val kinds: Seq[ActionKind] = Seq(
    ActionKind("Click Position", "mouse_icon.png", "Click a position in the window",
      _.isInstanceOf[ClickPosition], (c, a) => new ClickPositionEditor(c, a.collect { case x: ClickPosition => x })),
    ActionKind("Click Visual", "click_icon.png", "Click on an image in the window",
      _.isInstanceOf[ClickVisual], (c, a) => new ClickVisualEditor(c, a.collect { case x: ClickVisual => x })),
    ActionKind("Wait", "time-icon.png", "Pause for a while",
      _.isInstanceOf[Wait], (_, a) => new WaitEditor(a.collect { case x: Wait => x })),
    ActionKind("Type Text", "text_icon.png", "Type text at the cursor position",
      _.isInstanceOf[TypeText], (_, a) => new TypeTextEditor(a.collect { case x: TypeText => x })),
    ActionKind("Key Combination", "key_icon.png", "Press keys like Ctrl+S or Enter",
      _.isInstanceOf[KeyCombination], (_, a) => new KeyCombinationEditor(a.collect { case x: KeyCombination => x })),
    ActionKind("Scroll", "scroll_icon.png", "Scroll with the mouse wheel",
      _.isInstanceOf[Scroll], (c, a) => new ScrollEditor(c, a.collect { case x: Scroll => x })),
    ActionKind("Drag & Drop", "drag_icon.png", "Drag from one position to another",
      _.isInstanceOf[Drag], (c, a) => new DragEditor(c, a.collect { case x: Drag => x })),
    ActionKind("Wait for Image", "eye_icon.png", "Wait until an image appears or disappears",
      _.isInstanceOf[WaitForImage], (c, a) => new WaitForImageEditor(c, a.collect { case x: WaitForImage => x }))
  )

  def kindOf(action: MacroAction): ActionKind = kinds.find(_.matches(action)).get

  def editorFor(context: EditorContext, action: MacroAction): ActionEditor = kindOf(action).createEditor(context, Some(action))

  private[ui] def form(nodes: Node*): VBox = new VBox(14) {
    alignment = Pos.TopLeft
    padding = Insets(15, 25, 15, 25)
    children = nodes
  }

  private[ui] def section(title: String): Label = new Label(title) {
    style = "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #4682B4;"
    padding = Insets(6, 0, 0, 0)
  }
}

import ui.ActionEditors.{form, section}

// --- Reusable parts -------------------------------------------------------------

/** Mouse button, delay, number of clicks and movement speed. */
class ClickSettingsPane(existing: Option[ClickSettings]) {
  private val defaults = existing.getOrElse(ClickSettings())
  private val group = new ToggleGroup()
  // Prevent deselecting the active button, which would leave no button selected
  group.selectedToggle.onChange { (_, oldToggle, newToggle) =>
    if (newToggle == null && oldToggle != null) group.selectToggle(oldToggle)
  }

  private val buttons: Seq[(MouseButton, ToggleButton)] = MouseButton.all.map { b =>
    b -> new ToggleButton(b.label) {
      toggleGroup = group
      selected = b == defaults.button
      b match {
        case MouseButton.Left => graphic = icon("left-click-icon.png", 18)
        case MouseButton.Right => graphic = icon("right-click-icon.png", 18)
        case MouseButton.Middle => graphic = icon("mouse_icon.png", 18)
      }
    }
  }
  private val delaySpinner = intSpinner(0, 60000, defaults.delayAfterMs, 50)
  private val clicksSpinner = intSpinner(0, 10, defaults.clicks)
  private val speedSpinner = doubleSpinner(0.1, 5.0, defaults.mouseSpeed, 0.1)

  val nodes: Seq[Node] = Seq(
    formRow("Mouse button:", new HBox(8) {
      children = buttons.map(_._2)
    }),
    formRow("Number of clicks:", clicksSpinner),
    formRow("Delay after (ms):", delaySpinner),
    formRow("Movement speed:", speedSpinner)
  )

  def settings(): ClickSettings = ClickSettings(
    button = buttons.find(_._2.selected.value).map(_._1).getOrElse(MouseButton.Left),
    delayAfterMs = valueOf(delaySpinner),
    clicks = valueOf(clicksSpinner),
    mouseSpeed = valueOf(speedSpinner)
  )
}

/** Match threshold, search timeout and multi-scale search. */
class VisualSearchPane(existing: VisualSearch, timeoutLabel: String) {
  private val thresholdSpinner = doubleSpinner(0.5, 0.99, existing.threshold, 0.01)
  private val timeoutSpinner = intSpinner(1, 86400, existing.timeoutMs / 1000)
  private val multiScaleBox = new CheckBox("Also find zoomed versions (50 % - 200 %)") {
    selected = existing.multiScale
  }

  val nodes: Seq[Node] = Seq(
    formRow("Match threshold:", new HBox(8) {
      alignment = Pos.CenterLeft
      children = Seq(thresholdSpinner, hint("lower = more tolerant"))
    }),
    formRow(timeoutLabel, timeoutSpinner),
    formRow("", multiScaleBox)
  )

  def search(): VisualSearch = VisualSearch(valueOf(thresholdSpinner), valueOf(timeoutSpinner) * 1000, multiScaleBox.selected.value)
}

/** Shows a captured image and a button to (re)capture it. */
class ImageCapturePane(context: EditorContext, existing: Option[BufferedImage]) {
  var capturedImage: Option[BufferedImage] = existing

  private val imageView = new ImageView {
    preserveRatio = true
  }

  /** Shows the image at its original size, scaled down only if it is larger than the preview area. */
  private def show(img: BufferedImage): Unit = {
    imageView.image = toFxImage(img)
    imageView.fitWidth = img.getWidth.min(220).toDouble
    imageView.fitHeight = img.getHeight.min(90).toDouble
  }

  existing.foreach(show)
  private val placeholder = new Label("No image captured yet") {
    style = HintStyle
    visible <== imageView.image.isNull
    managed <== visible
  }

  private val captureButton: Button = new Button(if (existing.isDefined) "Recapture Image" else "Capture Image") {
    style = SuccessStyle
    onAction = _ => context.captureImage("Drag a rectangle around the element").foreach { captured =>
      capturedImage = Some(captured)
      show(captured)
      text = "Recapture Image"
    }
  }

  val node: Node = new VBox(8) {
    alignment = Pos.Center
    maxWidth = Double.MaxValue
    children = Seq(imageView, placeholder, captureButton)
  }
}

/** Shows a recorded position and a button to (re)capture it. */
class PointCapturePane(context: EditorContext, existing: Option[WindowPoint], optional: Boolean) {
  var point: Option[WindowPoint] = existing

  private val label = new Label {
    style = "-fx-font-size: 14px; -fx-text-fill: #2e7d32;"
  }

  private def update(): Unit = label.text = point.fold(if (optional) "Current mouse position" else "No position recorded yet") { p =>
    s"Position (${p.position.x}, ${p.position.y}) in a ${p.windowSize.width}x${p.windowSize.height} window"
  }

  update()

  private val captureButton: Button = new Button(if (existing.isDefined) "Recapture Position" else "Capture Position") {
    style = SuccessStyle
    onAction = _ => context.capturePoint("Click the position in the window").foreach { p =>
      point = Some(p)
      update()
      text = "Recapture Position"
    }
  }

  private val clearButton = new Button("Clear") {
    visible = optional
    managed = optional
    onAction = _ => {
      point = None
      update()
    }
  }

  val node: Node = new VBox(8) {
    alignment = Pos.Center
    maxWidth = Double.MaxValue
    children = Seq(new HBox(8) {
      alignment = Pos.Center
      children = Seq(captureButton, clearButton)
    }, label)
  }
}

// --- Editors ----------------------------------------------------------------------

class ClickPositionEditor(context: EditorContext, existing: Option[ClickPosition]) extends ActionEditor {
  private val pointPane = new PointCapturePane(context, existing.map(_.target), optional = false)
  private val clickPane = new ClickSettingsPane(existing.map(_.settings))

  val node: Node = form(Seq[Node](pointPane.node, section("Click")) ++ clickPane.nodes: _*)

  def result(): Either[String, MacroAction] = pointPane.point
    .map(p => ClickPosition(p, clickPane.settings()))
    .toRight("Please capture the position to click first.")
}

class ClickVisualEditor(context: EditorContext, existing: Option[ClickVisual]) extends ActionEditor {
  private val imagePane = new ImageCapturePane(context, existing.map(_.image))
  private val searchPane = new VisualSearchPane(existing.map(_.search).getOrElse(VisualSearch()), "Search for up to (s):")
  private val clickPane = new ClickSettingsPane(existing.map(_.settings))

  val node: Node = form(Seq[Node](imagePane.node, section("Search")) ++ searchPane.nodes ++ Seq(section("Click")) ++ clickPane.nodes: _*)

  def result(): Either[String, MacroAction] = imagePane.capturedImage
    .map(img => ClickVisual(img, searchPane.search(), clickPane.settings()))
    .toRight("Please capture the image to click on first.")
}

class WaitForImageEditor(context: EditorContext, existing: Option[WaitForImage]) extends ActionEditor {
  private val imagePane = new ImageCapturePane(context, existing.map(_.image))
  private val searchPane = new VisualSearchPane(existing.map(_.search).getOrElse(VisualSearch(timeoutMs = 30000)), "Give up after (s):")
  private val group = new ToggleGroup()
  private val appearButton = new RadioButton("appears") {
    toggleGroup = group
    selected = existing.forall(_.appear)
  }
  private val disappearButton = new RadioButton("disappears") {
    toggleGroup = group
    selected = existing.exists(!_.appear)
  }

  val node: Node = form(Seq[Node](
    imagePane.node,
    formRow("Wait until the image", new HBox(15) {
      children = Seq(appearButton, disappearButton)
    }),
    section("Search")
  ) ++ searchPane.nodes ++ Seq(hint("The macro stops with an error if the condition is not met in time.")): _*)

  def result(): Either[String, MacroAction] = imagePane.capturedImage
    .map(img => WaitForImage(img, appearButton.selected.value, searchPane.search()))
    .toRight("Please capture the image to wait for first.")
}

class WaitEditor(existing: Option[Wait]) extends ActionEditor {
  private val initialSeconds = existing.map(_.seconds).getOrElse(3)
  private val hoursSpinner = intSpinner(0, 23, initialSeconds / 3600, spinnerWidth = 75)
  private val minutesSpinner = intSpinner(0, 59, (initialSeconds % 3600) / 60, spinnerWidth = 75)
  private val secondsSpinner = intSpinner(0, 59, initialSeconds % 60, spinnerWidth = 75)
  Seq(hoursSpinner, minutesSpinner, secondsSpinner).foreach(s => (s.delegate.getValueFactory: AnyRef) match {
    // Wrap around, e.g. from 59 to 0 seconds
    case f: javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory => f.setWrapAround(true)
    case _ =>
  })

  private val clockCanvas = new Canvas(150, 150)

  private def drawClock(): Unit = {
    val gc = clockCanvas.graphicsContext2D
    val totalMinutes = hoursSpinner.value.value * 60 + minutesSpinner.value.value
    val angle = 360.0 * (totalMinutes % 60 + secondsSpinner.value.value / 60.0) / 60 // 60-minute clock
    val hours = totalMinutes / 60

    gc.clearRect(0, 0, 150, 150)
    gc.fill = Color.LightGray
    gc.fillOval(0, 0, 150, 150)

    // Color the area from 12 to the current position; darker for every full hour
    val intensity = Math.min(1.0, 0.2 + hours * 0.2)
    gc.fill = Color.rgb(135, 206, 250).deriveColor(0, 1, intensity, 0.5)
    gc.beginPath()
    gc.moveTo(75, 75)
    gc.lineTo(75, 2)
    gc.arc(75, 75, 73, 73, 90, -angle)
    gc.lineTo(75, 75)
    gc.closePath()
    gc.fill()

    gc.stroke = Color.Black
    for (i <- 0 until 60) {
      val a = Math.toRadians(i * 6)
      val inner = if (i % 5 == 0) 66 else 70
      gc.lineWidth = if (i % 5 == 0) 2 else 1
      gc.strokeLine(75 + inner * Math.sin(a), 75 - inner * Math.cos(a), 75 + 73 * Math.sin(a), 75 - 73 * Math.cos(a))
    }

    gc.stroke = Color.Blue
    gc.lineWidth = 3
    gc.strokeLine(75, 75, 75 + 60 * Math.sin(Math.toRadians(angle)), 75 - 60 * Math.cos(Math.toRadians(angle)))
    gc.fill = Color.Black
    gc.fillOval(72, 72, 6, 6)

    if (hours > 0) {
      gc.font = Font.font(14)
      gc.fillText(s"+${hours}h", 62, 100)
    }
  }

  Seq(hoursSpinner, minutesSpinner, secondsSpinner).foreach(_.value.onChange(drawClock()))
  drawClock()

  private def labeled(spinner: Spinner[Int], text: String) = new VBox(5) {
    alignment = Pos.Center
    children = Seq(spinner, new Label(text))
  }

  val node: Node = new VBox(20) {
    alignment = Pos.Center
    padding = Insets(20)
    children = Seq(
      new HBox(10) {
        alignment = Pos.Center
        children = Seq(labeled(hoursSpinner, "Hours"), labeled(minutesSpinner, "Minutes"), labeled(secondsSpinner, "Seconds"))
      },
      clockCanvas,
      new Label {
        style = "-fx-font-size: 18px; -fx-font-weight: bold;"
        text <== Bindings.createStringBinding(
          () => f"${hoursSpinner.value.value}%02d:${minutesSpinner.value.value}%02d:${secondsSpinner.value.value}%02d",
          hoursSpinner.value, minutesSpinner.value, secondsSpinner.value)
      }
    )
  }

  def result(): Either[String, MacroAction] =
    Right(Wait(valueOf(hoursSpinner) * 3600 + valueOf(minutesSpinner) * 60 + valueOf(secondsSpinner)))
}

class TypeTextEditor(existing: Option[TypeText]) extends ActionEditor {
  private val textArea = new TextArea {
    text = existing.map(_.text).getOrElse("")
    prefRowCount = 5
    wrapText = true
    promptText = "Text to type..."
  }

  val node: Node = form(
    new Label("Text to type:"),
    textArea,
    hint("Letters, digits, spaces and line breaks are typed key by key; other characters (symbols, umlauts, ...) " +
      "are pasted via the clipboard so they work with any keyboard layout.")
  )

  def result(): Either[String, MacroAction] =
    if (textArea.text.value.isEmpty) Left("Please enter the text to type.") else Right(TypeText(textArea.text.value))
}

class KeyCombinationEditor(existing: Option[KeyCombination]) extends ActionEditor {
  private var key: Option[(Int, String)] = existing.map(k => (k.keyCode, k.keyName))

  private val ctrlBox = new CheckBox("Ctrl") { selected = existing.exists(_.ctrl) }
  private val altBox = new CheckBox("Alt") { selected = existing.exists(_.alt) }
  private val shiftBox = new CheckBox("Shift") { selected = existing.exists(_.shift) }
  private val metaBox = new CheckBox("Win") { selected = existing.exists(_.meta) }

  private val preview = new Label {
    style = "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #4682B4;"
  }

  private def updatePreview(): Unit = preview.text = key.fold("-") { case (code, name) =>
    KeyCombination(code, name, ctrlBox.selected.value, altBox.selected.value, shiftBox.selected.value, metaBox.selected.value).summary
  }

  Seq(ctrlBox, altBox, shiftBox, metaBox).foreach(_.selected.onChange(updatePreview()))
  updatePreview()

  // Records the next key press including modifiers. JavaFX key codes are identical to java.awt VK_ codes.
  private val recorder = new TextField {
    editable = false
    promptText = "Click here and press the key combination"
    prefWidth = 300
    delegate.addEventFilter(JfxKeyEvent.KEY_PRESSED, (e: JfxKeyEvent) => {
      e.consume()
      val code = e.getCode.getCode
      if (!Keyboard.isModifier(code)) {
        key = Some((code, e.getCode.getName))
        ctrlBox.selected = e.isControlDown
        altBox.selected = e.isAltDown
        shiftBox.selected = e.isShiftDown
        metaBox.selected = e.isMetaDown
        updatePreview()
      }
    })
  }

  val node: Node = form(
    new Label("Key:"),
    recorder,
    formRow("Modifiers:", new HBox(12) {
      children = Seq(ctrlBox, altBox, shiftBox, metaBox)
    }, 100),
    formRow("Will press:", preview, 100),
    hint("Examples: Enter, Ctrl+S, Ctrl+Shift+T, Alt+F4, F5. The keys are sent to the window that has the focus.")
  )

  def result(): Either[String, MacroAction] = key
    .map { case (code, name) => KeyCombination(code, name, ctrlBox.selected.value, altBox.selected.value, shiftBox.selected.value, metaBox.selected.value) }
    .toRight("Please press the key combination in the input field first.")
}

class ScrollEditor(context: EditorContext, existing: Option[Scroll]) extends ActionEditor {
  private val directionBox = new ComboBox[String](Seq("Down", "Up")) {
    value = if (existing.exists(_.amount < 0)) "Up" else "Down"
  }
  private val amountSpinner = intSpinner(1, 100, existing.map(_.amount.abs).getOrElse(3))
  private val pointPane = new PointCapturePane(context, existing.flatMap(_.target), optional = true)

  val node: Node = form(
    formRow("Direction:", directionBox),
    formRow("Notches:", amountSpinner),
    section("Position (optional)"),
    pointPane.node,
    hint("Without a position, the window scrolls where the mouse currently is.")
  )

  def result(): Either[String, MacroAction] = {
    val amount = valueOf(amountSpinner)
    Right(Scroll(if (directionBox.value.value == "Up") -amount else amount, pointPane.point))
  }
}

class DragEditor(context: EditorContext, existing: Option[Drag]) extends ActionEditor {
  private var drag: Option[(Point, Point, Size)] = existing.map(d => (d.from, d.to, d.windowSize))

  private val label = new Label {
    style = "-fx-font-size: 14px; -fx-text-fill: #2e7d32;"
  }

  private def update(): Unit = label.text = drag.fold("No movement recorded yet") { case (from, to, _) =>
    s"From (${from.x}, ${from.y}) to (${to.x}, ${to.y})"
  }

  update()

  private val captureButton: Button = new Button(if (existing.isDefined) "Record Again" else "Record Drag") {
    style = SuccessStyle
    onAction = _ => context.captureDrag("Press the mouse at the start, drag, and release at the target").foreach { d =>
      drag = Some(d)
      update()
      text = "Record Again"
    }
  }

  private val buttonBox = new ComboBox[String](MouseButton.all.map(_.label)) {
    value = existing.map(_.button).getOrElse(MouseButton.Left).label
  }
  private val speedSpinner = doubleSpinner(0.1, 5.0, existing.map(_.mouseSpeed).getOrElse(0.5), 0.1)

  val node: Node = form(
    new VBox(8) {
      alignment = Pos.Center
      maxWidth = Double.MaxValue
      children = Seq(captureButton, label)
    },
    formRow("Mouse button:", buttonBox),
    formRow("Movement speed:", speedSpinner),
    hint("Useful for sliders, moving files or reordering items. Slow movements work best with most applications.")
  )

  def result(): Either[String, MacroAction] = drag
    .map { case (from, to, size) =>
      Drag(from, to, size, MouseButton.all.find(_.label == buttonBox.value.value).getOrElse(MouseButton.Left), valueOf(speedSpinner))
    }
    .toRight("Please record the drag movement first.")
}
