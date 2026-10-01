package win

import com.sun.jna.platform.WindowUtils
import com.sun.jna.platform.win32.WinDef.{HWND, POINT, RECT}
import com.sun.jna.platform.win32.WinGDI.{BITMAPINFO, BI_RGB, DIB_RGB_COLORS}
import com.sun.jna.platform.win32.{GDI32, User32, WinUser}
import com.sun.jna.win32.{StdCallLibrary, W32APIOptions}
import com.sun.jna.{Memory, Native, Pointer}
import engine.{Desktop, TargetWindow, WindowEntry}
import model.MouseButton

import java.awt.datatransfer.{DataFlavor, StringSelection}
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.awt.{Point, Rectangle, Robot, Toolkit}
import java.io.File
import javax.swing.filechooser.FileSystemView
import scala.jdk.CollectionConverters._
import scala.util.Try

/** User32 functions that are not mapped by JNA platform. */
private[win] trait User32Ext extends StdCallLibrary {
  def ClientToScreen(hWnd: HWND, point: POINT): Boolean

  def SetProcessDpiAwarenessContext(context: Pointer): Boolean
}

private[win] object User32Ext {
  lazy val INSTANCE: User32Ext = Native.load("user32", classOf[User32Ext], W32APIOptions.DEFAULT_OPTIONS)
}

object Win32Desktop {
  /**
   * Makes the process per-monitor DPI aware (v2), so that all Win32 coordinates are physical pixels.
   * Must be called before the first window is created. Fails harmlessly on old Windows versions or if
   * the awareness was already set by the Java launcher.
   */
  def enablePerMonitorDpiAwareness(): Unit =
    Try(User32Ext.INSTANCE.SetProcessDpiAwarenessContext(Pointer.createConstant(-4L)))
}

class Win32Window(val hwnd: HWND, initialTitle: String) extends TargetWindow {

  def title: String = {
    val length = User32.INSTANCE.GetWindowTextLength(hwnd)
    if (length <= 0) initialTitle
    else {
      val buffer = new Array[Char](length + 1)
      User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length)
      Native.toString(buffer)
    }
  }

  def isOpen: Boolean = User32.INSTANCE.IsWindow(hwnd)

  def bounds: Rectangle = {
    val rect = new RECT()
    User32.INSTANCE.GetWindowRect(hwnd, rect)
    rect.toRectangle
  }

  def clientArea: Rectangle = {
    val rect = new RECT()
    User32.INSTANCE.GetClientRect(hwnd, rect)
    val origin = new POINT(0, 0)
    User32Ext.INSTANCE.ClientToScreen(hwnd, origin)
    new Rectangle(origin.x, origin.y, rect.right - rect.left, rect.bottom - rect.top)
  }

  def resize(width: Int, height: Int): Unit = {
    val current = bounds
    // SWP_NOZORDER | SWP_NOACTIVATE
    User32.INSTANCE.SetWindowPos(hwnd, null, current.x, current.y, width, height, 0x0004 | 0x0010)
  }

  def toFront(): Unit = {
    // Only restore minimized windows; restoring would un-maximize maximized ones
    val placement = new WinUser.WINDOWPLACEMENT()
    User32.INSTANCE.GetWindowPlacement(hwnd, placement)
    if (placement.showCmd == WinUser.SW_SHOWMINIMIZED) User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE)
    User32.INSTANCE.SetForegroundWindow(hwnd)
    Thread.sleep(200)
  }

  def sameWindowAs(other: TargetWindow): Boolean = other match {
    case w: Win32Window => w.hwnd == hwnd
    case _ => false
  }
}

class Win32Desktop(ownWindowTitles: Set[String]) extends Desktop {

  private lazy val robot = new Robot()

  def windows(): Seq[WindowEntry] =
    WindowUtils.getAllWindows(true).asScala.toSeq
      .filter(w => w.getTitle.nonEmpty && !w.getTitle.contains("Task Manager") && !ownWindowTitles.contains(w.getTitle))
      .flatMap { w =>
        val icon = Option(WindowUtils.getWindowIcon(w.getHWND)).orElse(Try(fileIcon(w.getFilePath)).toOption)
        icon.map(i => WindowEntry(new Win32Window(w.getHWND, w.getTitle), Some(i)))
      }

  def virtualScreen: Rectangle = new Rectangle(
    User32.INSTANCE.GetSystemMetrics(WinUser.SM_XVIRTUALSCREEN),
    User32.INSTANCE.GetSystemMetrics(WinUser.SM_YVIRTUALSCREEN),
    User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXVIRTUALSCREEN),
    User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYVIRTUALSCREEN))

  // Win32 cursor functions work with physical pixels on all monitors, unlike java.awt.Robot which uses
  // scaled coordinates
  def cursorPosition: Point = {
    val point = new POINT()
    User32.INSTANCE.GetCursorPos(point)
    new Point(point.x, point.y)
  }

  def moveCursor(x: Int, y: Int): Unit = User32.INSTANCE.SetCursorPos(x.toLong, y.toLong)

  def mousePress(button: MouseButton): Unit = robot.mousePress(mask(button))

  def mouseRelease(button: MouseButton): Unit = robot.mouseRelease(mask(button))

  def keyPress(keyCode: Int): Unit = robot.keyPress(keyCode)

  def keyRelease(keyCode: Int): Unit = robot.keyRelease(keyCode)

  def scroll(notches: Int): Unit = robot.mouseWheel(notches)

  def paste(text: String): Unit = {
    val clipboard = Toolkit.getDefaultToolkit.getSystemClipboard
    val previous = Try(clipboard.getContents(null)).toOption.flatMap(Option(_))
      .filter(_.isDataFlavorSupported(DataFlavor.stringFlavor))

    clipboard.setContents(new StringSelection(text), null)
    robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
    robot.keyPress(java.awt.event.KeyEvent.VK_V)
    robot.keyRelease(java.awt.event.KeyEvent.VK_V)
    robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
    // Give the target application time to read the clipboard before restoring it
    Thread.sleep(150)
    previous.foreach(p => Try(clipboard.setContents(p, null)))
  }

  /** Copies the screen area with GDI, which uses physical pixels and works across monitors. */
  def capture(area: Rectangle): BufferedImage = {
    val screenDC = User32.INSTANCE.GetDC(null)
    val memoryDC = GDI32.INSTANCE.CreateCompatibleDC(screenDC)
    val bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(screenDC, area.width, area.height)
    try {
      val previous = GDI32.INSTANCE.SelectObject(memoryDC, bitmap)
      // SRCCOPY | CAPTUREBLT (includes layered windows)
      GDI32.INSTANCE.BitBlt(memoryDC, 0, 0, area.width, area.height, screenDC, area.x, area.y, 0x00CC0020 | 0x40000000)
      GDI32.INSTANCE.SelectObject(memoryDC, previous)

      val info = new BITMAPINFO()
      info.bmiHeader.biWidth = area.width
      info.bmiHeader.biHeight = -area.height // top-down
      info.bmiHeader.biPlanes = 1
      info.bmiHeader.biBitCount = 32
      info.bmiHeader.biCompression = BI_RGB

      val buffer = new Memory(area.width.toLong * area.height * 4)
      GDI32.INSTANCE.GetDIBits(screenDC, bitmap, 0, area.height, buffer, info, DIB_RGB_COLORS)

      val image = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB)
      image.setRGB(0, 0, area.width, area.height, buffer.getIntArray(0, area.width * area.height), 0, area.width)
      image
    } finally {
      GDI32.INSTANCE.DeleteObject(bitmap)
      GDI32.INSTANCE.DeleteDC(memoryDC)
      User32.INSTANCE.ReleaseDC(null, screenDC)
    }
  }

  private def mask(button: MouseButton): Int = button match {
    case MouseButton.Left => InputEvent.BUTTON1_DOWN_MASK
    case MouseButton.Middle => InputEvent.BUTTON2_DOWN_MASK
    case MouseButton.Right => InputEvent.BUTTON3_DOWN_MASK
  }

  private def fileIcon(path: String): BufferedImage = {
    val icon = FileSystemView.getFileSystemView.getSystemIcon(new File(path))
    val scaleFactor = 1.3
    val width = (icon.getIconWidth * scaleFactor).toInt
    val height = (icon.getIconHeight * scaleFactor).toInt
    val bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = bi.createGraphics
    icon.paintIcon(null, g, 0, 0)
    g.dispose()
    bi
  }
}
