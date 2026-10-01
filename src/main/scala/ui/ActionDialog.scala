package ui

import model.MacroAction
import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.control.{Button, Label, ScrollPane}
import scalafx.scene.layout._
import scalafx.stage.{Modality, Stage, Window}
import ui.UiKit._

/**
 * Dialog to add a new action (choose a type, then configure it) or to edit an existing one.
 *
 * @param onDone called with the configured action when the user confirms
 */
class ActionDialog(state: AppState, parentWindow: Window, existing: Option[MacroAction])(onDone: MacroAction => Unit) {

  val stage: Stage = new Stage {
    title = if (existing.isDefined) "Edit Action" else "Add Action"
    width = 560
    height = 690
    icons += appIcon
    initModality(Modality.WindowModal)
    initOwner(parentWindow)
  }

  private val context = new EditorContext(state, () => stage)
  private var editor: Option[ActionEditor] = None
  private val contentArea = new ScrollPane {
    fitToWidth = true
    vgrow = Priority.Always
    // Only make the border invisible: "-fx-background: transparent" would also turn the text color white
    style = "-fx-background-color: transparent;"
  }

  private val typeButtons: Seq[(ActionKind, Button)] = ActionEditors.kinds.map { kind =>
    kind -> new Button {
      graphic = new VBox(4) {
        alignment = Pos.Center
        children = Seq(icon(kind.iconFile, 30), new Label(kind.name) {
          style = "-fx-font-size: 11px;"
          wrapText = true
          maxWidth = 100
          alignment = Pos.Center
        })
      }
      prefWidth = 118
      prefHeight = 72
      focusTraversable = false
      onAction = _ => select(kind)
    }
  }

  /** Shows the editor for the given action type. */
  def select(kind: ActionKind): Unit = {
    val newEditor = kind.createEditor(context, existing.filter(kind.matches))
    editor = Some(newEditor)
    contentArea.content = newEditor.node
    typeButtons.foreach { case (k, b) =>
      b.style = if (k == kind) "-fx-background-color: #4CAF50; -fx-text-fill: white;" else ""
    }
  }

  private def overview = new FlowPane(15, 15) {
    padding = Insets(20)
    alignment = Pos.Center
    children = ActionEditors.kinds.map { kind =>
      new HBox(8) {
        alignment = Pos.CenterLeft
        prefWidth = 230
        children = Seq(icon(kind.iconFile, 26), new VBox(2) {
          children = Seq(
            new Label(kind.name) {
              style = "-fx-font-weight: bold;"
            },
            new Label(kind.description) {
              style = "-fx-font-size: 11px;"
              wrapText = true
              maxWidth = 190
            })
        })
      }
    }
  }

  private def confirm(): Unit = editor match {
    case Some(e) => e.result() match {
      case Right(action) =>
        onDone(action)
        stage.close()
      case Left(message) => showWarning(stage, "Incomplete action", message)
    }
    case None => showWarning(stage, "No action selected", "Please choose an action type first.")
  }

  stage.scene = new Scene {
    root = new BorderPane {
      top = if (existing.isDefined) null else new FlowPane(6, 6) {
        padding = Insets(10)
        alignment = Pos.Center
        children = typeButtons.map(_._2)
      }
      center = contentArea
      bottom = new HBox(10) {
        alignment = Pos.CenterRight
        padding = Insets(10)
        children = Seq(
          button(if (existing.isDefined) "Save Changes" else "Add Action", SuccessStyle)(confirm()),
          button("Cancel", DangerStyle)(stage.close())
        )
      }
    }
  }

  existing match {
    case Some(action) => select(ActionEditors.kindOf(action))
    case None => contentArea.content = overview
  }
}
