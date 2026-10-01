package ui

import javafx.scene.input.{ClipboardContent, DragEvent, TransferMode, MouseEvent => JfxMouseEvent}
import model.{MacroAction, MacroFile}
import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control._
import scalafx.scene.image.ImageView
import scalafx.scene.layout._
import scalafx.scene.Node
import scalafx.stage.FileChooser.ExtensionFilter
import scalafx.stage.{FileChooser, Stage}
import ui.UiKit._

import java.io.File
import scala.util.{Failure, Success, Try}

/** Step 2: define, reorder, save and load the actions. */
class ActionListView(state: AppState, stage: Stage, onPrevious: () => Unit, onNext: () => Unit) {

  private val actions = state.actions

  private val listView: ListView[MacroAction] = new ListView[MacroAction](actions) {
    vgrow = Priority.Always
    placeholder = new Label("No actions yet. Click \"Add Action\" or open a saved macro.") {
      style = HintStyle
    }
    // updateItem is also called when only the index changes (e.g. after removing an action),
    // so the row number and the button handlers never refer to a stale position
    cellFactory = (_: ListView[MacroAction]) => new ListCell[MacroAction](new ActionCell)
    selectionModel().setSelectionMode(SelectionMode.Single)
  }

  // Clicking the selected row again deselects it, so new actions can be appended at the end
  private var previousSelectedIndex = -1
  listView.onMouseClicked = (e: scalafx.scene.input.MouseEvent) => if (e.clickCount == 1) {
    val index = listView.selectionModel().getSelectedIndex
    if (previousSelectedIndex != index) previousSelectedIndex = index
    else {
      listView.selectionModel().clearSelection()
      previousSelectedIndex = -1
    }
  }

  /** A row with drag & drop support for reordering. */
  private class ActionCell extends javafx.scene.control.ListCell[MacroAction] {
    setPrefHeight(42)

    override def updateItem(action: MacroAction, empty: Boolean): Unit = {
      super.updateItem(action, empty)
      setText(null)
      setGraphic(if (empty || action == null) null else createRow(action, this).delegate)
    }

    setOnDragDetected((e: JfxMouseEvent) => if (getItem != null && !state.busy.value) {
      val dragboard = startDragAndDrop(TransferMode.MOVE)
      val content = new ClipboardContent()
      content.putString(getIndex.toString)
      dragboard.setContent(content)
      dragboard.setDragView(snapshot(null, null))
      e.consume()
    })

    setOnDragOver((e: DragEvent) => {
      if (e.getGestureSource != this && e.getDragboard.hasString) e.acceptTransferModes(TransferMode.MOVE)
      e.consume()
    })

    setOnDragEntered((e: DragEvent) => if (e.getGestureSource != this) setStyle("-fx-border-color: #4682B4; -fx-border-width: 2 0 0 0;"))

    setOnDragExited((_: DragEvent) => setStyle(""))

    setOnDragDropped((e: DragEvent) => {
      val from = Try(e.getDragboard.getString.toInt).getOrElse(-1)
      if (from >= 0 && from < actions.size) {
        val target = if (isEmpty) actions.size - 1 else getIndex.min(actions.size - 1)
        val action = actions.remove(from)
        actions.insert(target, action)
        listView.selectionModel().clearAndSelect(target)
        e.setDropCompleted(true)
      }
      e.consume()
    })
  }

  private def createRow(action: MacroAction, cell: javafx.scene.control.ListCell[MacroAction]): Node = {
    def indexOfCell = Some(cell.getIndex).filter(i => i >= 0 && i < actions.size)

    def smallButton(text: String, color: String, tip: String)(onClick: Int => Unit) = new Button(text) {
      style = s"-fx-background-color: $color; -fx-text-fill: white; -fx-font-size: 13px; -fx-min-width: 32px;"
      tooltip = tip
      disable <== state.busy
      onAction = _ => indexOfCell.foreach(onClick)
    }

    val kind = ActionEditors.kindOf(action)
    val preview: Seq[Node] = action match {
      case a: MacroAction.ClickVisual => Seq(thumbnail(a.image))
      case a: MacroAction.WaitForImage => Seq(thumbnail(a.image))
      case _ => Seq.empty
    }

    new HBox(6) {
      alignment = Pos.CenterLeft
      children = Seq[Node](
        new Label("☰") {
          style = "-fx-text-fill: #9e9e9e; -fx-cursor: move;"
          tooltip = "Drag to reorder"
        },
        new Label(s"${cell.getIndex + 1}.") {
          style = "-fx-font-size: 14px; -fx-font-weight: bold;"
          minWidth = 28
        },
        icon(kind.iconFile, 20),
        new Label(action.typeName) {
          style = "-fx-font-size: 14px;"
          minWidth = Region.USE_PREF_SIZE
        }
      ) ++ preview ++ Seq[Node](
        new Label(action.summary) {
          style = "-fx-font-size: 13px; -fx-text-fill: #555555;"
          textOverrun = OverrunStyle.Ellipsis
          minWidth = 0
          hgrow = Priority.Always
          maxWidth = Double.MaxValue
        },
        smallButton("⚙", "#4682B4", "Edit")(index => editAction(index)),
        smallButton("⧉", "#78909C", "Duplicate")(index => {
          actions.insert(index + 1, actions(index))
          listView.selectionModel().clearAndSelect(index + 1)
        }),
        smallButton("X", "#FF4136", "Remove")(index => actions.remove(index))
      )
    }
  }

  private def thumbnail(image: java.awt.image.BufferedImage): Node = new ImageView(toFxImage(image)) {
    preserveRatio = true
    fitHeight = 28
    fitWidth = 90
  }

  private def editAction(index: Int): Unit =
    new ActionDialog(state, stage, Some(actions(index)))(updated => actions(index) = updated).stage.showAndWait()

  private def addAction(): Unit = {
    val selected = listView.selectionModel().getSelectedIndex
    new ActionDialog(state, stage, None)({ action =>
      if (selected >= 0) {
        actions.insert(selected + 1, action)
        listView.selectionModel().clearAndSelect(selected + 1)
      } else actions += action
    }).stage.showAndWait()
  }

  // --- Files -----------------------------------------------------------------------

  private def fileChooser(titleText: String): FileChooser = new FileChooser {
    title = titleText
    extensionFilters += new ExtensionFilter("SimpleMacro files", s"*.${MacroFile.Extension}")
    state.currentFile.value.flatMap(f => Option(f.getParentFile)).filter(_.isDirectory).foreach(initialDirectory = _)
  }

  private def newMacro(): Unit =
    if (actions.isEmpty || confirm(stage, "New macro", "Remove all actions and start a new macro?")) {
      actions.clear()
      state.currentFile.value = None
    }

  private def openMacro(): Unit = Option(fileChooser("Open Macro").showOpenDialog(stage)).foreach { file =>
    Try(MacroFile.load(file)) match {
      case Success(loaded) =>
        actions.setAll(loaded: _*)
        state.currentFile.value = Some(file)
        state.log.info(s"Opened macro ${file.getName} with ${loaded.size} action(s)")
      case Failure(e) => showError(stage, "Could not open macro", e.getMessage)
    }
  }

  private def saveMacro(file: File): Unit = Try(MacroFile.save(file, actions.toSeq)) match {
    case Success(_) =>
      state.currentFile.value = Some(file)
      state.log.info(s"Saved macro to ${file.getAbsolutePath}")
    case Failure(e) => showError(stage, "Could not save macro", e.getMessage)
  }

  private def saveMacroAs(): Unit = {
    val chooser = fileChooser("Save Macro")
    chooser.initialFileName = state.currentFile.value.map(_.getName).getOrElse(s"macro.${MacroFile.Extension}")
    Option(chooser.showSaveDialog(stage)).foreach { file =>
      val withExtension = if (file.getName.contains('.')) file else new File(file.getPath + s".${MacroFile.Extension}")
      saveMacro(withExtension)
    }
  }

  private def toolbarButton(text: String, tip: String)(action: => Unit) = new Button(text) {
    tooltip = tip
    onAction = _ => action
  }

  private val fileLabel = new Label {
    style = HintStyle
    text <== state.currentFile.map(_.map(f => s"File: ${f.getName}").getOrElse("Not saved yet (changes are saved automatically for the next start)"))
  }

  val root: BorderPane = new BorderPane {
      top = new VBox(6) {
        padding = Insets(10, 15, 8, 15)
        children = Seq(
          heading("Step 2: Define Actions"),
          new HBox(8) {
            alignment = Pos.CenterLeft
            children = Seq(
              toolbarButton("New", "Start a new macro")(newMacro()),
              toolbarButton("Open...", "Open a saved macro")(openMacro()),
              toolbarButton("Save", "Save the macro")(state.currentFile.value.fold(saveMacroAs())(saveMacro)),
              toolbarButton("Save As...", "Save the macro to a new file")(saveMacroAs()),
              fileLabel
            )
          }
        )
      }
      center = new VBox(12) {
        padding = Insets(0, 15, 0, 15)
        children = Seq(
          listView,
          new Button("Add Action") {
            style = SuccessStyle
            graphic = icon("add_icon.png", 22)
            maxWidth = Double.MaxValue
            text <== listView.selectionModel().selectedItemProperty().isNull.map(empty => if (empty) "Add Action" else "Add Action after Selection")
            onAction = _ => addAction()
          }
        )
      }
      bottom = new HBox(10) {
        alignment = Pos.Center
        padding = Insets(15)
        children = Seq(
          button("Previous", PrimaryStyle)(onPrevious()),
          button("Next", PrimaryStyle) {
            if (actions.nonEmpty) onNext()
            else showWarning(stage, "No actions defined", "You have to define at least one action.")
          }
        )
      }
  }
}
