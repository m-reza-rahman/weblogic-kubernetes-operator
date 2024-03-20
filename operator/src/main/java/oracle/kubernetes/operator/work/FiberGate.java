// Copyright (c) 2018, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import oracle.kubernetes.operator.work.Fiber.CompletionCallback;
import oracle.kubernetes.operator.work.Fiber.FiberExecutor;
import org.jetbrains.annotations.NotNull;

/**
 * Allows at most one running Fiber per key value. However, rather than queue later arriving Fibers
 * this class cancels the earlier arriving Fibers. For the operator, this makes sense as domain
 * presence Fibers that come later will always complete or correct work that may have been
 * in-flight.
 */
public class FiberGate {
  private final ScheduledExecutorService scheduledExecutorService;

  /** A map of domain UIDs to the fiber charged with running processing on that domain. **/
  private final Map<String, Fiber> gateMap = new ConcurrentHashMap<>();

  /**
   * Constructor taking Engine for running Fibers.
   *
   * @param scheduledExecutorService Executor
   */
  public FiberGate(ScheduledExecutorService scheduledExecutorService) {
    this.scheduledExecutorService = scheduledExecutorService;
  }

  /**
   * Access map of current fibers.
   * @return Map of fibers in this gate
   */
  public Map<String, Fiber> getCurrentFibers() {
    return new HashMap<>(gateMap);
  }

  /**
   * Starts Fiber that cancels any earlier running Fibers with the same domain UID. Fiber map is not
   * updated if no Fiber is started.
   *
   * @param domainUid the UID for which a fiber should be started
   * @param strategy Step for Fiber to begin with
   * @param packet Packet
   * @param callback Completion callback
   */
  public void startFiber(String domainUid, Step strategy, Packet packet, CompletionCallback callback) {
    requestNewFiberStart(domainUid, strategy, packet, callback);
  }

  /**
   * Starts Fiber only if there is no running Fiber with the same key. Fiber map is not updated if
   * no Fiber is started.
   *
   * @param domainUid the UID for which a fiber should be started
   * @param strategy Step for Fiber to begin with
   * @param packet Packet
   * @param callback Completion callback
   */
  public void startFiberIfNoCurrentFiber(
      String domainUid, Step strategy, Packet packet, CompletionCallback callback) {
    requestNewFiberStart(domainUid, strategy, packet, callback);
  }

  /**
   * Starts a new fiber only if the current running fiber is associated with the specified domain UID.
   * @param domainUid  the UID for which a fiber should be started
   * @param strategy Step for Fiber to begin with
   * @param packet Packet
   * @param callback Completion callback
   */
  public void startNewFiberIfCurrentFiberMatches(
      String domainUid, Step strategy, Packet packet, CompletionCallback callback) {
    requestNewFiberStart(domainUid, strategy, packet, callback);
  }

  /**
   * Starts Fiber only if the last started Fiber matches the given old Fiber.
   *
   * @param domainUid the UID for which a fiber should be started
   * @param strategy Step for Fiber to begin with
   * @param packet Packet
   * @param callback Completion callback
   */
  private synchronized void requestNewFiberStart(
      String domainUid, Step strategy, Packet packet, CompletionCallback callback) {
    new FiberRequest(domainUid, strategy, packet, callback).invoke();
  }

  private class FiberRequest {

    private final String domainUid;
    private final Fiber fiber;

    FiberRequest(String domainUid, Step steps, Packet packet, CompletionCallback callback) {
      this.domainUid = domainUid;

      fiber = new Fiber(new FiberExecutorImpl(), steps, packet, new FiberGateCompletionCallback(callback, domainUid));
    }

    void invoke() {
      fiber.start();
    }

    private class FiberExecutorImpl implements FiberExecutor {
      @Override
      public Cancellable schedule(Fiber fiber, Duration duration) {
        ScheduledFuture<?> future = scheduledExecutorService.schedule(
                () -> execute(fiber), TimeUnit.MILLISECONDS.convert(duration), TimeUnit.MILLISECONDS);
        return () -> future.cancel(true);
      }

      @Override
      public void execute(@NotNull Fiber fiber) {
        Fiber existing = gateMap.put(domainUid, fiber);
        if (existing != null) {
          existing.cancel();
        }
      }
    }
  }

  private class FiberGateCompletionCallback implements CompletionCallback {

    private final CompletionCallback callback;
    private final String domainUid;

    public FiberGateCompletionCallback(CompletionCallback callback, String domainUid) {
      this.callback = callback;
      this.domainUid = domainUid;
    }

    @Override
    public void onCompletion(Packet packet) {
      Fiber fiber = packet.getFiber();
      try {
        callback.onCompletion(packet);
      } finally {
        gateMap.remove(domainUid, fiber);
      }
    }

    @Override
    public void onThrowable(Packet packet, Throwable throwable) {
      Fiber fiber = packet.getFiber();
      try {
        callback.onThrowable(packet, throwable);
      } finally {
        gateMap.remove(domainUid, fiber);
      }
    }
  }
}
