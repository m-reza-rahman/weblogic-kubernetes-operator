// Copyright (c) 2018, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Collection of {@link Fiber}s. Owns an {@link ScheduledExecutorService} to run them.
 */
public class Engine {
  private final ScheduledExecutorService threadPool;

  /**
   * Creates engine with the specified executor.
   *
   * @param threadPool Executor
   */
  public Engine(ScheduledExecutorService threadPool) {
    this.threadPool = threadPool;
  }

  /**
   * Returns the executor.
   *
   * @return executor
   */
  public ScheduledExecutorService getExecutor() {
    return threadPool;
  }

  void addRunnable(Runnable runnable) {
    threadPool.execute(runnable);
  }

  /**
   * Creates a new fiber in a suspended state.
   *
   * <p>To start the returned fiber, call {@link Fiber#start(Step,Packet,Fiber.CompletionCallback)}.
   * It will start executing the given {@link Step} with the given {@link Packet}.
   *
   * @return new Fiber
   */
  public Fiber createFiber() {
    return new Fiber(this);
  }

  Fiber createChildFiber(Fiber parent) {
    return new Fiber(this, parent);
  }
}
