// Copyright (c) 2018, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;

import static oracle.kubernetes.operator.work.Step.THROWABLE;

/**
 * Represents the execution of one processing flow.
 */
public final class Fiber implements AsyncFiber {

  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");
  private static final int NOT_COMPLETE = 0;
  private static final int DONE = 1;
  private static final int SUSPENDED = 2;
  private static final int CANCELLED = 3;
  private static final ThreadLocal<Fiber> CURRENT_FIBER = new ThreadLocal<>();
  /** Used to allocate unique number for each fiber. */
  private static final AtomicInteger iotaGen = new AtomicInteger();

  public final Engine owner;
  private final Fiber parent;
  private final int id;
  private final AtomicInteger status = new AtomicInteger(NOT_COMPLETE);
  private final CompletionCallback completionCallback;
  private Packet packet;
  /** The next action for this Fiber. */
  private Collection<Fiber> children = null;

  // for unit test only
  public Fiber() {
    this(null);
  }

  /**
   * Create Fiber with a completion callback.
   * @param completionCallback The callback to be invoked when the processing is finished and the
   *     final packet is available.
   */
  public Fiber(CompletionCallback completionCallback) {
    this(completionCallback, null);
  }

  Fiber(CompletionCallback completionCallback, Engine engine) {
    this(completionCallback, engine, null);
  }

  Fiber(CompletionCallback completionCallback, Engine engine, Fiber parent) {
    this.completionCallback = completionCallback;
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
   * Starts the execution of this fiber asynchronously.
   *
   * @param stepline The first step of the stepline that will act on the packet.
   * @param packet The packet to be passed to {@code Step#apply(Packet)}.
   */
  public void start(Step stepline, Packet packet) {
    if (status.get() == NOT_COMPLETE) {
      LOGGER.finer("{0} started", getName());
      packet.setFiber(this);

      owner.addRunnable(() -> {
        if (status.get() == NOT_COMPLETE) {
          clearThreadInterruptedStatus();

          final Fiber oldFiber = CURRENT_FIBER.get();
          CURRENT_FIBER.set(this);
          try {
            doRun(stepline, packet);
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

  private void doRun(Step stepline, Packet packet) {
    this.packet = packet;
    try {
      stepline.apply(packet);
      if (status.compareAndSet(NOT_COMPLETE, DONE) && completionCallback != null) {
        Throwable t = (Throwable) packet.get(THROWABLE);
        if (t != null) {
          completionCallback.onThrowable(packet, t);
        } else {
          completionCallback.onCompletion(packet);
        }
      }
    } catch (Throwable t) {
      status.set(DONE);
      if (completionCallback != null) {
        completionCallback.onThrowable(packet, t);
      }
    }
  }

  void delay(Step stepline, Packet packet, long delay, TimeUnit unit) {
    if (status.compareAndSet(NOT_COMPLETE, SUSPENDED)) {
      owner.getExecutor().schedule(() -> {
        if (status.compareAndSet(SUSPENDED, NOT_COMPLETE)) {
          stepline.apply(packet);
        }
      }, delay, unit);
    }
  }

  void forkJoin(Step step, Packet packet, Collection<StepAndPacket> startDetails) {
    final AtomicInteger count = new AtomicInteger(startDetails.size());
    final List<Throwable> throwables = new ArrayList<>();
    CompletionCallback callback = new CompletionCallback() {
      @Override
      public void onThrowable(Packet p, Throwable throwable) {
        synchronized (throwables) {
          throwables.add(throwable);
        }
        mark();
      }

      @Override
      public void onCompletion(Packet p) {
        mark();
      }

      public void mark() {
        int current = count.decrementAndGet();
        if (current <= 0) {
          if (status.compareAndSet(SUSPENDED, NOT_COMPLETE)) {
            if (throwables.isEmpty()) {
              step.apply(packet);
            } else {
              if (completionCallback != null) {
                Throwable t = (throwables.size() == 1) ? throwables.get(0) : new MultiThrowable(throwables);
                completionCallback.onThrowable(packet, t);
              }
            }
          }
        }
      }
    };

    // start forked fibers
    for (StepAndPacket sp : startDetails) {
      createChildFiber(callback).start(sp.step, Optional.ofNullable(sp.packet).orElse(packet.copy()));
    }
  }

  private String getStatus() {
    return switch (status.get()) {
      case NOT_COMPLETE -> "NOT_COMPLETE";
      case SUSPENDED -> "SUSPENDED";
      case DONE -> "DONE";
      case CANCELLED -> "CANCELLED";
      default -> "UNKNOWN: " + status.get();
    };
  }

  public boolean isCancelled() {
    return CANCELLED == status.get();
  }

  /**
   * Creates a child Fiber. If this Fiber is cancelled, so will all of the children.
   *
   * @return Child fiber
   */
  @Override
  public Fiber createChildFiber(CompletionCallback completionCallback) {
    synchronized (this) {
      if (children == null) {
        children = new ArrayList<>();
      }
      Fiber child = owner.createChildFiber(completionCallback, this);

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
    status.getAndUpdate(state -> state != DONE ? CANCELLED : DONE);

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
}
