package engine

import org.scalatest.funsuite.AnyFunSuite

import java.awt.Point
import java.time.{Duration, LocalDateTime}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

class SchedulerAndPathSpec extends AnyFunSuite {

  test("a start time in the past is moved to the next day") {
    val now = LocalDateTime.of(2026, 1, 1, 12, 0)
    assert(MacroScheduler.firstRun(now.minusMinutes(2), now) == now.minusMinutes(2).plusDays(1))
    assert(MacroScheduler.firstRun(now.minusSeconds(30), now) == now)
    assert(MacroScheduler.firstRun(now.plusMinutes(1), now) == now.plusMinutes(1))
    assert(MacroScheduler.nextRun(now, Duration.ofMinutes(5), 2) == now.plusMinutes(10))
  }

  test("the scheduler runs the task the requested number of times and then finishes") {
    val scheduler = new MacroScheduler
    val runs = new AtomicInteger()
    val finished = new CountDownLatch(1)
    scheduler.schedule(LocalDateTime.now().plusNanos(1000000), 3, Duration.ofMillis(50)) { _ =>
      runs.incrementAndGet()
      true
    }(() => finished.countDown())
    assert(finished.await(5, TimeUnit.SECONDS))
    assert(runs.get == 3)
    assert(!scheduler.isActive)
  }

  test("a failed run cancels the remaining runs") {
    val scheduler = new MacroScheduler
    val runs = new AtomicInteger()
    val finished = new CountDownLatch(1)
    scheduler.schedule(LocalDateTime.now().plusNanos(1000000), 5, Duration.ofMillis(50)) { _ =>
      runs.incrementAndGet()
      false
    }(() => finished.countDown())
    assert(finished.await(5, TimeUnit.SECONDS))
    Thread.sleep(200)
    assert(runs.get == 1)
  }

  test("cancel stops a pending schedule") {
    val scheduler = new MacroScheduler
    val runs = new AtomicInteger()
    scheduler.schedule(LocalDateTime.now().plusSeconds(2), 1, Duration.ofSeconds(1)) { _ =>
      runs.incrementAndGet()
      true
    }(() => ())
    assert(scheduler.isActive)
    assert(scheduler.cancel())
    assert(!scheduler.isActive)
    assert(!scheduler.cancel())
  }

  test("mouse paths end exactly at the target") {
    val path = MousePath.humanLike(new Point(0, 0), new Point(300, 200), random = () => 0.5)
    assert(path.last == MousePath.Step(300, 200, path.last.delayMs))
    assert(path.size >= 10)
    assert(path.forall(_.delayMs >= 0))
  }
}
