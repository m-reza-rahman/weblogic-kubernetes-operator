// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import oracle.kubernetes.operator.work.Fiber.CompletionCallback;

/**
 * Allows at most one running Fiber per key value. However, rather than queue later arriving Fibers
 * this class cancels the earlier arriving Fibers. For the operator, this makes sense as domain
 * presence Fibers that come later will always complete or correct work that may have been
 * in-flight.
 */
public class FiberGate {
  private final Engine engine;

  /** A map of domain UIDs to the fiber charged with running processing on that domain. **/
  private final Map<String, Fiber> gateMap = new ConcurrentHashMap<>();

  private final Fiber placeholder;

  /**
   * Constructor taking Engine for running Fibers.
   *
   * @param engine Engine
   */
  public FiberGate(Engine engine) {
    this.engine = engine;
    this.placeholder = engine.createFiber();
  }

  /**
   * Access map of current fibers.
   * @return Map of fibers in this gate
   */
  public Map<String, Fiber> getCurrentFibers() {
    return new HashMap<>(gateMap);
  }

  public Executor getExecutor() {
    return engine.getExecutor();
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
    requestNewFiberStart(domainUid, null, strategy, packet, callback);
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
    requestNewFiberStart(domainUid, placeholder, strategy, packet, callback);
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
    requestNewFiberStart(domainUid, Fiber.getCurrentIfSet(), strategy, packet, callback);
  }

  /**
   * Starts Fiber only if the last started Fiber matches the given old Fiber.
   *
   * @param domainUid the UID for which a fiber should be started
   * @param old Expected last Fiber
   * @param strategy Step for Fiber to begin with
   * @param packet Packet
   * @param callback Completion callback
   */
  private synchronized void requestNewFiberStart(
      String domainUid, Fiber old, Step strategy, Packet packet, CompletionCallback callback) {
    new FiberRequest(domainUid, old, strategy, packet, callback).invoke();
  }

  private class FiberRequest {

    private final String domainUid;
    private final Fiber fiber;
    private final CompletionCallback gateCallback;
    private Fiber old;
    private final Step steps;
    private final Packet packet;

    FiberRequest(String domainUid, Fiber old, Step steps, Packet packet, CompletionCallback callback) {
      this.domainUid = domainUid;
      this.old = old;
      this.steps = steps;
      this.packet = packet;

      fiber = engine.createFiber();
      gateCallback = new FiberGateCompletionCallback(callback, domainUid, fiber);
    }

    void invoke() {
      if (isAllowed()) {
        fiber.start(steps, packet, gateCallback);
      }
    }

    private boolean isAllowed() {
      Fiber existing = null;
      try {
        if (old == null) {
          existing = gateMap.put(domainUid, fiber);
          return true;
        } else if (old == placeholder) {
          existing = gateMap.putIfAbsent(domainUid, fiber);
          return existing == null;
        } else {
          boolean result = gateMap.replace(domainUid, old, fiber);
          if (result) {
            existing = old;
          }
          return result;
        }
      } finally {
        if (existing != null) {
          existing.cancel();
        }
      }
    }
  }

  private class FiberGateCompletionCallback implements CompletionCallback {

    private final CompletionCallback callback;
    private final String domainUid;
    private final Fiber fiber;

    public FiberGateCompletionCallback(CompletionCallback callback, String domainUid, Fiber fiber) {
      this.callback = callback;
      this.domainUid = domainUid;
      this.fiber = fiber;
    }

    @Override
    public void onCompletion(Packet packet) {
      try {
        callback.onCompletion(packet);
      } finally {
        gateMap.remove(domainUid, fiber);
      }
    }

    @Override
    public void onThrowable(Packet packet, Throwable throwable) {
      try {
        callback.onThrowable(packet, throwable);
      } finally {
        gateMap.remove(domainUid, fiber);
      }
    }
  }
}
