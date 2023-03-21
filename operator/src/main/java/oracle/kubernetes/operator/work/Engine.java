// Copyright (c) 2018, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collection of {@link Fiber}s. Owns an {@link Executor} to run them.
 */
public class Engine {
  private final Executor threadPool;

  /**
   * Creates engine with the specified executor.
   *
   * @param threadPool Executor
   */
  public Engine(Executor threadPool) {
    this.threadPool = threadPool;
  }

  /**
   * Returns the executor.
   *
   * @return executor
   */
  public Executor getExecutor() {
    return threadPool;
  }

  void addRunnable(Runnable runnable) {
    threadPool.execute(runnable);
  }

  /**
   * Schedule a task for repeated execution with an initial delay and then a delay between each repetition. All
   * times are in seconds. The repetition can be cancelled using the return value.
   * @param command Command
   * @param initialDelay Initial delay
   * @param delay Ongoing delay
   * @return Control to cancel further repetition
   */
  public Cancellable scheduleWithFixedDelay(Runnable command, long initialDelay, long delay) {
    AtomicBoolean stopSignal = new AtomicBoolean(false);
    threadPool.execute(() -> {
      try {
        Thread.sleep(initialDelay * 1000);
        while (!stopSignal.get()) {
          command.run();
          Thread.sleep(delay * 1000);
        }
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    });
    return () -> stopSignal.compareAndSet(false, true);
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
