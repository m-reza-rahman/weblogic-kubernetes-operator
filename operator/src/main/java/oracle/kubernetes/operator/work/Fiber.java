// Copyright (c) 2018, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.io.Serial;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.kubernetes.client.extended.controller.reconciler.Result;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import org.jetbrains.annotations.NotNull;

import static oracle.kubernetes.operator.work.Step.THROWABLE;
import static oracle.kubernetes.operator.work.Step.adapt;

/**
 * Represents the execution of one processing flow.
 */
public final class Fiber {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");
  private static final ThreadLocal<Fiber> CURRENT_FIBER = new ThreadLocal<>();
  /** Used to allocate unique number for each fiber. */
  private static final AtomicInteger iotaGen = new AtomicInteger();

  private final int id;
  private final FiberExecutor fiberExecutor;
  private final CompletionCallback completionCallback;
  private Packet packet;
  /** The next action for this Fiber. */

  // for unit test only
  public Fiber(FiberExecutor fiberExecutor) {
    this(fiberExecutor, null);
  }

  public Fiber(ScheduledExecutorService scheduledExecutorService) {
    this(fromScheduled(scheduledExecutorService));
  }

  public Fiber(ScheduledExecutorService scheduledExecutorService, CompletionCallback completionCallback) {
    this(fromScheduled(scheduledExecutorService), completionCallback);
  }

  /**
   * Create Fiber with a completion callback.
   * @param completionCallback The callback to be invoked when the processing is finished and the
   *     final packet is available.
   */
  public Fiber(FiberExecutor fiberExecutor, CompletionCallback completionCallback) {
    this.fiberExecutor = fiberExecutor;
    this.completionCallback = completionCallback;
    id = iotaGen.incrementAndGet(); // FIXME: rollover
  }

  /**
   * Gets the current fiber that's running, if set.
   *
   * @return Current fiber
   */
  public static Fiber getCurrentIfSet() {
    return CURRENT_FIBER.get();
  }

  /**
   * Starts the execution of this fiber asynchronously.
   *
   * @param stepline The first step of the stepline that will act on the packet.
   * @param packet The packet to be passed to {@code Step#apply(Packet)}.
   */
  public void start(Step stepline, Packet packet) {
    LOGGER.finer("{0} started", getName());
    fiberExecutor.execute(() -> doRun(stepline, packet));
  }

  private boolean invokeAndPotentiallyRequeue(Step stepline, Packet packet) {
    Result result = stepline.apply(packet);
    if (result == null || result.isRequeue()) {
      fiberExecutor.schedule(() -> doRun(stepline, packet), result.getRequeueAfter());
      return false;
    }
    return true;
  }

  private void doRun(Step stepline, Packet packet) {
    clearThreadInterruptedStatus();

    final Fiber oldFiber = CURRENT_FIBER.get();
    CURRENT_FIBER.set(this);
    try {
      this.packet = packet;
      try {
        if ((stepline == null || invokeAndPotentiallyRequeue(adapt(stepline, packet), packet))
                && completionCallback != null) {
          Throwable t = (Throwable) packet.remove(THROWABLE);
          if (t != null) {
            completionCallback.onThrowable(packet, t);
          } else {
            completionCallback.onCompletion(packet);
          }
        }
      } catch (Throwable t) {
        if (completionCallback != null) {
          completionCallback.onThrowable(packet, t);
        }
      }
    } finally {
      if (oldFiber == null) {
        CURRENT_FIBER.remove();
      } else {
        CURRENT_FIBER.set(oldFiber);
      }
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  void clearThreadInterruptedStatus() {
    Thread.interrupted();
  }

  private String getName() {
    StringBuilder sb = new StringBuilder();
    sb.append("fiber-");
    sb.append(id);
    return sb.toString();
  }

  @Override
  public String toString() {
    return getName();
  }

  /**
   * Gets the current {@link Packet} associated with this fiber. This method returns null if no
   * packet has been associated with the fiber yet.
   *
   * @return the packet
   */
  public Packet getPacket() {
    return packet;
  }

  /**
   * Callback to be invoked when a {@link Fiber} finishes execution.
   */
  public interface CompletionCallback {
    /**
     * Indicates that the fiber has finished its execution. Since the processing flow runs
     * asynchronously, this method maybe invoked by a different thread than any of the threads that
     * started it or run a part of stepline.
     *
     * @param packet The packet
     */
    void onCompletion(Packet packet);

    /**
     * Indicates that the fiber has finished its execution with a throwable. Since the processing
     * flow runs asynchronously, this method maybe invoked by a different thread than any of the
     * threads that started it or run a part of stepline.
     *
     * @param packet The packet
     * @param throwable The throwable
     */
    void onThrowable(Packet packet, Throwable throwable);
  }

  public record StepAndPacket(Step step, Packet packet) {
  }

  /** Multi-exception. */
  public static class MultiThrowable extends RuntimeException {
    @Serial
    private static final long serialVersionUID  = 1L;
    private final transient List<Throwable> throwables;

    private MultiThrowable(List<Throwable> throwables) {
      super(throwables.get(0));
      this.throwables = throwables;
    }

    /**
     * The multiple exceptions wrapped by this exception.
     *
     * @return Multiple exceptions
     */
    public List<Throwable> getThrowables() {
      return throwables;
    }
  }

  interface FiberExecutor extends Executor {
    Cancellable schedule(Runnable runnable, Duration duration);
  }

  private static FiberExecutor fromScheduled(ScheduledExecutorService scheduledExecutorService) {
    return new FiberExecutor() {
      @Override
      public Cancellable schedule(Runnable runnable, Duration duration) {
        ScheduledFuture<?> future = scheduledExecutorService.schedule(runnable,
                TimeUnit.MILLISECONDS.convert(duration), TimeUnit.MILLISECONDS);
        return () -> future.cancel(true);
      }

      @Override
      public void execute(@NotNull Runnable command) {
        scheduledExecutorService.execute(command);
      }
    };
  }
}
