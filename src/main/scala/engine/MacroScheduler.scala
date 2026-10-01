package engine

import java.time.{Duration, LocalDateTime}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}

object MacroScheduler {
  /**
   * The first run: the given time today, or tomorrow if that time has already passed. A start time in the last
   * minute (e.g. the current time with seconds = 0) means "now".
   */
  def firstRun(startTime: LocalDateTime, now: LocalDateTime): LocalDateTime =
    if (startTime.isBefore(now.minusMinutes(1))) startTime.plusDays(1)
    else if (startTime.isBefore(now)) now
    else startTime

  def nextRun(firstRun: LocalDateTime, interval: Duration, completedRuns: Int): LocalDateTime =
    firstRun.plus(interval.multipliedBy(completedRuns.toLong))
}

/** Runs a task a number of times at a fixed rate. Only one schedule is active at a time. */
class MacroScheduler {

  private var active: Option[ScheduledExecutorService] = None

  def isActive: Boolean = synchronized(active.isDefined)

  /**
   * @param task      called with the run number (1-based); returns false to cancel the remaining runs
   * @param onFinished called on the scheduler thread after the last run or when the task cancelled the schedule
   * @return the time of the first run
   */
  def schedule(startTime: LocalDateTime, runs: Int, interval: Duration)(task: Int => Boolean)(onFinished: () => Unit): LocalDateTime = synchronized {
    cancel()
    val now = LocalDateTime.now()
    val first = MacroScheduler.firstRun(startTime, now)

    val executor = Executors.newSingleThreadScheduledExecutor((r: Runnable) => {
      val thread = new Thread(r, "macro-scheduler")
      thread.setDaemon(true)
      thread
    })
    active = Some(executor)

    val runCount = new AtomicInteger(0)

    def finish(): Unit = {
      val wasActive = synchronized {
        val current = active.contains(executor)
        if (current) active = None
        current
      }
      executor.shutdown()
      if (wasActive) onFinished()
    }

    // A single-threaded executor never runs two executions at the same time; if a run takes
    // longer than the interval, the next run starts right after it.
    executor.scheduleAtFixedRate(() => {
      val run = runCount.incrementAndGet()
      if (run <= runs) {
        val proceed = try task(run) catch {
          case _: InterruptedException => false
        }
        if (!proceed || run == runs) finish()
      }
    }, Duration.between(now, first).toMillis.max(0), interval.toMillis.max(1), TimeUnit.MILLISECONDS)

    first
  }

  /** Cancels the active schedule (interrupting a running execution). Returns true if there was one. */
  def cancel(): Boolean = synchronized {
    active match {
      case Some(executor) =>
        active = None
        executor.shutdownNow()
        true
      case None => false
    }
  }
}
