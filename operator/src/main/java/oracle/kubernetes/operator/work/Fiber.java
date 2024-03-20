// Copyright (c) 2018, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.io.Serial;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
public final class Fiber implements Runnable {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");
  private static final ThreadLocal<Fiber> CURRENT_FIBER = new ThreadLocal<>();
  /** Used to allocate unique number for each fiber. */
  private static final AtomicInteger iotaGen = new AtomicInteger();

  private final int id;
  private final FiberExecutor fiberExecutor;
  private final CompletionCallback completionCallback;
  private final Step stepline;
  private final Packet packet;
  private final AtomicBoolean isCancelled = new AtomicBoolean(false);

  public Fiber(FiberExecutor fiberExecutor, Step stepline, Packet packet) {
    this(fiberExecutor, stepline, packet, null);
  }

  public Fiber(ScheduledExecutorService scheduledExecutorService, Step stepline, Packet packet) {
    this(fromScheduled(scheduledExecutorService), stepline, packet);
  }

  public Fiber(ScheduledExecutorService scheduledExecutorService, Step stepline, Packet packet, CompletionCallback completionCallback) {
    this(fromScheduled(scheduledExecutorService), stepline, packet, completionCallback);
  }

  /**
   * Create Fiber with a completion callback.
   * @param completionCallback The callback to be invoked when the processing is finished and the
   *     final packet is available.
   */
  public Fiber(FiberExecutor fiberExecutor, Step stepline, Packet packet, CompletionCallback completionCallback) {
    this.fiberExecutor = fiberExecutor;
    this.stepline = stepline;
    this.packet = packet;
    this.completionCallback = completionCallback;
    id = iotaGen.incrementAndGet();
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
   */
  public void start() {
    LOGGER.finer("{0} started", getName());
    fiberExecutor.execute(this);
  }

  private boolean invokeAndPotentiallyRequeue(Step stepline, Packet packet) {
    Result result = stepline.apply(packet);
    if (result == null || result.isRequeue()) {
      fiberExecutor.schedule(this, result.getRequeueAfter());
      return false;
    }
    return true;
  }

  @Override
  public void run() {
    if (!isCancelled()) {
      clearThreadInterruptedStatus();

      final Fiber oldFiber = CURRENT_FIBER.get();
      CURRENT_FIBER.set(this);
      try {
        try {
          if ((stepline == null || invokeAndPotentiallyRequeue(adapt(stepline, packet), packet))
                  && !isCancelled()
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
  }

  public boolean isCancelled() {
    return isCancelled.get();
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
   * Cancels this fiber.
   */
  public void cancel() {
    isCancelled.set(true);
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

  interface FiberExecutor {
    void execute(Fiber fiber);

    Cancellable schedule(Fiber fiber, Duration duration);
  }

  private static FiberExecutor fromScheduled(ScheduledExecutorService scheduledExecutorService) {
    return new FiberExecutor() {
      @Override
      public Cancellable schedule(Fiber fiber, Duration duration) {
        ScheduledFuture<?> future = scheduledExecutorService.schedule(fiber,
                TimeUnit.MILLISECONDS.convert(duration), TimeUnit.MILLISECONDS);
        return () -> future.cancel(true);
      }

      @Override
      public void execute(@NotNull Fiber fiber) {
        scheduledExecutorService.execute(fiber);
      }
    };
  }
}
