package engine

import org.slf4j.LoggerFactory

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters._

object MacroLog {
  sealed abstract class Level(val label: String)

  case object Info extends Level("INFO")
  case object Warn extends Level("WARN")
  case object Error extends Level("ERROR")

  case class Entry(time: LocalTime, level: Level, message: String) {
    override def toString: String = s"${time.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}  ${level.label.padTo(5, ' ')}  $message"
  }
}

/** Thread-safe log of what a macro did. Listeners are called on the logging thread. */
class MacroLog(maxEntries: Int = 1000) {

  import MacroLog._

  private val logger = LoggerFactory.getLogger("SimpleMacro")
  private val buffer = new java.util.ArrayDeque[Entry]()
  private val listeners = new CopyOnWriteArrayList[Entry => Unit]()

  def info(message: String): Unit = add(Info, message)

  def warn(message: String): Unit = add(Warn, message)

  def error(message: String): Unit = add(Error, message)

  def entries: Seq[Entry] = buffer.synchronized(buffer.asScala.toList)

  def clear(): Unit = buffer.synchronized(buffer.clear())

  def addListener(listener: Entry => Unit): Unit = listeners.add(listener)

  def removeListener(listener: Entry => Unit): Unit = listeners.remove(listener)

  private def add(level: Level, message: String): Unit = {
    val entry = Entry(LocalTime.now(), level, message)
    buffer.synchronized {
      buffer.addLast(entry)
      while (buffer.size > maxEntries) buffer.removeFirst()
    }
    level match {
      case Info => logger.info(message)
      case Warn => logger.warn(message)
      case Error => logger.error(message)
    }
    listeners.asScala.foreach(_(entry))
  }
}
