// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;

/**
 * Represents the execution of one processing flow.
 */
public final class Fiber implements AsyncFiber {

  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");
  private static final int NOT_COMPLETE = 0;
  private static final int DONE = 1;
  private static final int CANCELLED = 2;
  private static final ThreadLocal<Fiber> CURRENT_FIBER = new ThreadLocal<>();
  /** Used to allocate unique number for each fiber. */
  private static final AtomicInteger iotaGen = new AtomicInteger();

  public final Engine owner;
  private final Fiber parent;
  private final int id;
  private final AtomicInteger status = new AtomicInteger(NOT_COMPLETE);
  private Packet packet;
  /** The next action for this Fiber. */
  private Collection<Fiber> children = null;

  // for unit test only
  public Fiber() {
    this(null);
  }

  Fiber(Engine engine) {
    this(engine, null);
  }

  Fiber(Engine engine, Fiber parent) {
    this.owner = engine;
    this.parent = parent;
    id = (parent == null) ? iotaGen.incrementAndGet() : (parent.children.size() + 1);
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
   * Use this fiber's executor to schedule an operation for some time in the future.
   * @param timeout the interval before the check should run, in units
   * @param unit the unit of time that defines the interval
   * @param runnable the operation to run
   */
  @Override
  public void scheduleOnce(long timeout, TimeUnit unit, Runnable runnable) {
    this.owner.getExecutor().schedule(runnable, timeout, unit);
  }

  /**
   * Starts the execution of this fiber asynchronously.
   *
   * @param stepline The first step of the stepline that will act on the packet.
   * @param packet The packet to be passed to {@code Step#apply(Packet)}.
   * @param completionCallback The callback to be invoked when the processing is finished and the
   *     final packet is available.
   */
  public void start(Step stepline, Packet packet, CompletionCallback completionCallback) {
    if (status.get() == NOT_COMPLETE) {
      LOGGER.finer("{0} started", getName());
      packet.setFiber(this);

      owner.addRunnable(() -> {
        if (status.get() == NOT_COMPLETE) {
          clearThreadInterruptedStatus();

          final Fiber oldFiber = CURRENT_FIBER.get();
          CURRENT_FIBER.set(this);
          try {
            doRun(stepline, packet, completionCallback);
          } finally {
            if (oldFiber == null) {
              CURRENT_FIBER.remove();
            } else {
              CURRENT_FIBER.set(oldFiber);
            }
          }
        }
      });
    }
  }

  private void doRun(Step stepline, Packet packet, CompletionCallback completionCallback) {
    this.packet = packet;
    try {
      stepline.apply(packet);
      if (status.compareAndSet(NOT_COMPLETE, DONE) && completionCallback != null) {
        completionCallback.onCompletion(packet);
      }
    } catch (Throwable t) {
      if (completionCallback != null) {
        completionCallback.onThrowable(packet, t);
      }
    }
  }

  private String getStatus() {
    switch (status.get()) {
      case NOT_COMPLETE: return "NOT_COMPLETE";
      case DONE: return "DONE";
      case CANCELLED: return "CANCELLED";
      default: return "UNKNOWN: " + status.get();
    }
  }

  /**
   * Creates a child Fiber. If this Fiber is cancelled, so will all of the children.
   *
   * @return Child fiber
   */
  @Override
  public Fiber createChildFiber() {
    synchronized (this) {
      if (children == null) {
        children = new ArrayList<>();
      }
      Fiber child = owner.createChildFiber(this);

      children.add(child);
      if (status.get() != NOT_COMPLETE) {
        // Race condition where child is created after parent is cancelled or done
        child.status.set(CANCELLED);
      }

      return child;
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  void clearThreadInterruptedStatus() {
    Thread.interrupted();
  }

  private String getName() {
    StringBuilder sb = new StringBuilder();
    if (parent != null) {
      sb.append(parent.getName());
      sb.append("-child-");
    } else {
      sb.append("fiber-");
    }
    sb.append(id);
    return sb.toString();
  }

  @Override
  public String toString() {
    return getName() + " " + getStatus();
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
    // Mark fiber as cancelled, if not already done
    status.compareAndSet(NOT_COMPLETE, CANCELLED);

    if (LOGGER.isFinerEnabled()) {
      LOGGER.finer("{0} cancelled", getName());
    }

    if (children != null) {
      children.forEach(Fiber::cancel);
    }
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
}
