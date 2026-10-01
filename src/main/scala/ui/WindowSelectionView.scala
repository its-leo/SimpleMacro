package ui

import engine.WindowEntry
import javafx.animation.{Animation, KeyFrame, Timeline}
import scalafx.Includes._
import scalafx.collections.ObservableBuffer
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.{Button, ListCell, ListView, TextField}
import scalafx.scene.layout._
import scalafx.stage.Stage
import ui.UiKit._

/** Step 1: choose the window the macro runs in. */
class WindowSelectionView(state: AppState, stage: Stage, onNext: () => Unit) {

  private var searchTerm = ""

  private def loadWindows(): Seq[WindowEntry] =
    state.desktop.windows().filter(_.window.title.toLowerCase.contains(searchTerm))

  private val items = ObservableBuffer.from(loadWindows())

  private val listView = new ListView[WindowEntry](items) {
    vgrow = Priority.Always
    cellFactory = (_: ListView[WindowEntry]) => new ListCell[WindowEntry](new javafx.scene.control.ListCell[WindowEntry] {
      setPrefHeight(40)

      override def updateItem(entry: WindowEntry, empty: Boolean): Unit = {
        super.updateItem(entry, empty)
        if (empty || entry == null) {
          setText(null)
          setGraphic(null)
        } else {
          setGraphic(entry.icon.map(i => new scalafx.scene.image.ImageView(toFxImage(i)) {
            preserveRatio = true
            fitHeight = 24
          }.delegate).orNull)
          setText(entry.window.title.split(" - ").last)
          setStyle("-fx-font-size: 14px;")
        }
      }
    })
    onMouseClicked = e => if (e.clickCount == 2) next()
  }

  private def refresh(): Unit = {
    val selected = Option(listView.selectionModel().getSelectedItem).map(_.window).orElse(state.selectedWindow)
    items.setAll(loadWindows(): _*)
    // Restore the selection if the window still exists
    selected.foreach { window =>
      val index = items.indexWhere(_.window.sameWindowAs(window))
      if (index >= 0) listView.selectionModel().select(index)
    }
  }

  private val refreshTimeline = new Timeline(new KeyFrame(javafx.util.Duration.seconds(5), (_: javafx.event.ActionEvent) => refresh()))
  refreshTimeline.setCycleCount(Animation.INDEFINITE)
  refreshTimeline.play()
  refresh()

  private def next(): Unit = Option(listView.selectionModel().getSelectedItem) match {
    case Some(entry) =>
      state.selectedWindow = Some(entry.window)
      onNext()
    case None => showWarning(stage, "No window selected", "Please select the window the macro should run in.")
  }

  def dispose(): Unit = refreshTimeline.stop()

  val root: BorderPane = new BorderPane {
      top = new HBox {
        alignment = Pos.CenterLeft
        padding = Insets(10, 0, 10, 15)
        children = Seq(heading("Step 1: Select a Window"))
      }
      center = new VBox(10) {
        padding = Insets(0, 15, 15, 15)
        children = Seq(
          new HBox(10) {
            children = Seq(
              new TextField {
                promptText = "Filter windows..."
                style = "-fx-font-size: 14px;"
                hgrow = Priority.Always
                text.onChange { (_, _, value) =>
                  searchTerm = value.toLowerCase
                  refresh()
                }
              },
              new Button("⟳") {
                style = "-fx-font-size: 14px;"
                tooltip = "Refresh the window list"
                onAction = _ => refresh()
              }
            )
          },
          listView
        )
      }
      bottom = new HBox {
        alignment = Pos.BottomRight
        padding = Insets(0, 15, 15, 15)
        children = Seq(button("Next", PrimaryStyle)(next()))
      }
  }
}
