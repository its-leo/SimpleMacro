package ui

import javafx.embed.swing.SwingFXUtils
import scalafx.Includes._
import scalafx.geometry.Pos
import scalafx.scene.Node
import scalafx.scene.control.Alert.AlertType
import scalafx.scene.control._
import scalafx.scene.image.{Image, ImageView}
import scalafx.scene.layout.HBox
import scalafx.stage.Window

import java.awt.image.BufferedImage
import scala.util.Try

/** Shared styles and small UI helpers. */
object UiKit {

  val PrimaryStyle = "-fx-background-color: #4682B4; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
  val SuccessStyle = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
  val DangerStyle = "-fx-background-color: #FF4136; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 15;"
  val NeutralStyle = "-fx-background-color: #D3D3D3; -fx-font-size: 14px; -fx-padding: 8 15;"
  val HintStyle = "-fx-font-size: 11px; -fx-text-fill: #6b6b6b;"

  def appIcon: Image = new Image(getClass.getResourceAsStream("/icons/robot_icon.png"))

  def icon(name: String, size: Double): ImageView = new ImageView(new Image(getClass.getResourceAsStream(s"/icons/$name"))) {
    fitWidth = size
    fitHeight = size
    preserveRatio = true
    smooth = true
  }

  def toFxImage(image: BufferedImage): Image = new Image(SwingFXUtils.toFXImage(image, null))

  def heading(text: String): Label = new Label(text) {
    style = "-fx-font-size: 26px; -fx-font-weight: bold;"
  }

  def hint(text: String): Label = new Label(text) {
    style = HintStyle
    wrapText = true
  }

  def button(text: String, buttonStyle: String)(action: => Unit): Button = new Button(text) {
    style = buttonStyle
    onAction = _ => action
  }

  def formRow(label: String, control: Node, labelWidth: Double = 140): HBox = new HBox(10) {
    alignment = Pos.CenterLeft
    children = Seq(
      new Label(label) {
        prefWidth = labelWidth
        minWidth = labelWidth
      },
      control
    )
  }

  def showWarning(parent: Window, titleText: String, message: String): Unit = alert(AlertType.Warning, parent, titleText, message)

  def showError(parent: Window, titleText: String, message: String): Unit = alert(AlertType.Error, parent, titleText, message)

  def confirm(parent: Window, titleText: String, message: String): Boolean =
    new Alert(AlertType.Confirmation) {
      if (parent != null) initOwner(parent)
      title = titleText
      headerText = message
    }.showAndWait().contains(ButtonType.OK)

  private def alert(alertType: AlertType, parent: Window, titleText: String, message: String): Unit = {
    new Alert(alertType) {
      if (parent != null) initOwner(parent)
      title = titleText
      headerText = message
    }.showAndWait()
  }

  // --- Spinners ---------------------------------------------------------------

  def intSpinner(min: Int, max: Int, initial: Int, step: Int = 1, spinnerWidth: Double = 110): Spinner[Int] =
    commitOnFocusLost(new Spinner[Int](min, max, initial.max(min).min(max), step) {
      editable = true
      prefWidth = spinnerWidth
    })

  def doubleSpinner(min: Double, max: Double, initial: Double, step: Double, spinnerWidth: Double = 110): Spinner[Double] =
    commitOnFocusLost(new Spinner[Double](min, max, initial.max(min).min(max), step) {
      editable = true
      prefWidth = spinnerWidth
    })

  /** Editable spinners only commit typed text on ENTER; also commit when the editor loses focus. */
  private def commitOnFocusLost[T](spinner: Spinner[T]): Spinner[T] = {
    spinner.delegate.getEditor.focusedProperty.addListener((_, _, focused) => if (!focused) commit(spinner))
    spinner
  }

  private def commit[T](spinner: Spinner[T]): Unit = {
    val jfxSpinner = spinner.delegate
    if (jfxSpinner.isEditable && Try(jfxSpinner.commitValue()).isFailure) {
      // Invalid input: restore the last valid value
      jfxSpinner.getEditor.setText(jfxSpinner.getValueFactory.getConverter.toString(jfxSpinner.getValue))
    }
  }

  /** The spinner value including text that was typed but not yet committed. */
  def valueOf[T](spinner: Spinner[T]): T = {
    commit(spinner)
    spinner.value.value
  }
}
