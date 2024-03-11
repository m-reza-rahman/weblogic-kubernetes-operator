// Copyright (c) 2019, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import oracle.kubernetes.operator.calls.RequestBuilder;
import oracle.kubernetes.operator.calls.ResponseStep;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.steps.DefaultResponseStep;
import oracle.kubernetes.operator.tuning.TuningParameters;
import oracle.kubernetes.operator.work.AsyncFiber;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.DomainResource;

import static oracle.kubernetes.operator.ProcessingConstants.INTROSPECTOR_JOB_FAILURE_THROWABLE;
import static oracle.kubernetes.operator.ProcessingConstants.MAKE_RIGHT_DOMAIN_OPERATION;
import static oracle.kubernetes.operator.helpers.KubernetesUtils.getDomainUidLabel;

/**
 * This class is the base for steps that must suspend while waiting for a resource to become ready. It is typically
 * implemented as a part of a {@link Watcher} and relies on callbacks from that watcher to proceed.
 * @param <T> the type of resource handled by this step
 */
abstract class WaitForReadyStep<T extends KubernetesObject> extends Step {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  static NextStepFactory nextStepFactory = WaitForReadyStep::createMakeDomainRightStep;

  protected static Step createMakeDomainRightStep(WaitForReadyStep<?>.Callback callback,
                                           DomainPresenceInfo info, Step next) {
    return RequestBuilder.DOMAIN.get(info.getNamespace(), info.getDomainName(),
        new MakeRightDomainStep<>(callback, null));
  }

  static int getWatchBackstopRecheckDelaySeconds() {
    return TuningParameters.getInstance().getWatchTuning().getWatchBackstopRecheckDelay();
  }

  static int getWatchBackstopRecheckCount() {
    return TuningParameters.getInstance().getWatchTuning().getWatchBackstopRecheckCount();
  }

  final T initialResource;
  final String resourceName;

  /**
   * Creates a step which will only proceed once the specified resource is ready.
   * @param resource the resource to watch
   * @param next the step to run once the resource is ready
   */
  WaitForReadyStep(T resource, Step next) {
    this(null, resource, next);
  }

  WaitForReadyStep(String resourceName, T resource, Step next) {
    super(next);
    this.initialResource = resource;
    this.resourceName = resourceName;
  }

  @Override
  protected String getDetail() {
    return getResourceName();
  }

  /**
   * Returns true if the specified resource is deemed "ready." Different steps may define readiness in different ways.
   * @param resource the resource to check
   * @return true if processing can proceed
   */
  abstract boolean isReady(T resource);

  /**
   * Returns true if the cached resource is not found during periodic listing.
   * @param cachedResource cached resource to check
   * @param isNotFoundOnRead Boolean indicating if resource is not found in call response.
   *
   * @return true if cached resource not found on read
   */
  boolean onReadNotFoundForCachedResource(T cachedResource, boolean isNotFoundOnRead) {
    return false;
  }

  /**
   * Returns true if the callback for this resource should be processed. This is typically used to exclude
   * resources which have changed but are not yet ready, or else different instances with the same name.
   * This default implementation processes all callbacks.
   * 
   * @param resource the resource to check
   * @return true if the resource is expected
   */
  boolean shouldProcessCallback(T resource) {
    return true;
  }

  /**
   * Returns the metadata associated with the resource.
   * @param resource the resource to check
   * @return a Kubernetes metadata object containing the namespace and name
   */
  abstract V1ObjectMeta getMetadata(T resource);

  /**
   * Registers a callback for changes to the resource.
   * @param name the name of the resource to watch
   * @param callback the callback to invoke when a change is reported
   */
  abstract void addCallback(String name, Consumer<T> callback);

  /**
   * Unregisters a callback for the specified resource name.
   * @param name the name of the resource to stop watching
   * @param callback the previously registered callback
   */
  abstract void removeCallback(String name, Consumer<T> callback);

  /**
   * Creates a {@link Step} that reads the specified resource asynchronously and then invokes the specified response.
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   * @param domainUid the identifier of the domain that the resource is associated with
   * @param responseStep the step which should be invoked once the resource has been read
   * @return the created step
   */
  abstract Step createReadAsyncStep(String name, String namespace, String domainUid, ResponseStep<T> responseStep);

  /**
   * Updates the packet when the resource is declared ready. The default implementation does nothing.
   * @param packet the packet to update
   * @param resource the now-ready resource
   */
  void updatePacket(Packet packet, T resource) {
  }

  /**
   * Determines whether the state of the resource requires the fiber to be terminated.
   * This default implementation always returns false; if it returns true, {@link #createTerminationException(Object)}
   * must return a non-null result
   * @param resource the resource to check
   * @return true if the fiber should be terminated
   */
  boolean shouldTerminateFiber(T resource) {
    return false;
  }

  /**
   * Creates an exception to report as the fiber completion if the fiber is being terminated.
   * @param resource the resource from which the exception should be created
   * @return an exception. Must not return null if
   */
  Throwable createTerminationException(T resource) {
    return null;
  }

  /**
   * Log a message to indicate that we have started waiting for the resource to become ready.
   * This default implementation does nothing.
   * @param name the name of the resource
   */
  void logWaiting(String name) {
    // no-op
  }

  @Override
  public final StepAction apply(Packet packet) {
    if (shouldTerminateFiber(initialResource)) {
      return doTerminate(createTerminationException(initialResource), packet);
    } else if (isReady(initialResource)) {
      return doNext(packet);
    }

    logWaiting(getResourceName());
    return doSuspend(packet, (resumable) -> resumeWhenReady(packet, resumable));
  }

  // Registers a callback for updates to the specified resource and
  // verifies that we haven't already missed the update.
  private void resumeWhenReady(Packet packet, Resumable resumable) {
    Callback callback = new Callback(resumable);
    addCallback(getResourceName(), callback);
    checkUpdatedResource(packet, packet.getFiber(), callback);
  }

  // It is possible that the watch event was received between the time the step was created, and the time the callback
  // was registered. Just in case, we will check the latest resource value in Kubernetes and process the resource
  // if it is now ready
  private void checkUpdatedResource(Packet packet, AsyncFiber fiber, Callback callback) {
    fiber
        .createChildFiber(null)
        .start(
            createReadAndIfReadyCheckStep(callback),
            packet.copy());
  }

  Step createReadAndIfReadyCheckStep(Callback callback) {
    if (initialResource != null) {
      return createReadAsyncStep(getResourceName(), getNamespace(), getDomainUid(), resumeIfReady(callback));
    } else {
      return new ReadAndIfReadyCheckStep(getResourceName(), resumeIfReady(callback), getNext());
    }
  }

  protected abstract ResponseStep<T> resumeIfReady(Callback callback);

  private String getNamespace() {
    return getMetadata(initialResource).getNamespace();
  }

  private String getDomainUid() {
    return getDomainUidLabel(getMetadata(initialResource));
  }

  @Override
  public String getResourceName() {
    return initialResource != null ? getMetadata(initialResource).getName() : resourceName;
  }


  private class ReadAndIfReadyCheckStep extends Step {
    private final String resourceName;
    private final ResponseStep<T> responseStep;

    ReadAndIfReadyCheckStep(String resourceName, ResponseStep<T> responseStep, Step next) {
      super(next);
      this.resourceName = resourceName;
      this.responseStep = responseStep;
    }

    @Override
    public StepAction apply(Packet packet) {
      DomainPresenceInfo info = (DomainPresenceInfo) packet.get(ProcessingConstants.DOMAIN_PRESENCE_INFO);
      return doNext(createReadAsyncStep(resourceName, info.getNamespace(),
              info.getDomainUid(), responseStep), packet);
    }

  }

  static class MakeRightDomainStep<V extends KubernetesObject> extends DefaultResponseStep<V> {
    private final WaitForReadyStep<?>.Callback callback;

    MakeRightDomainStep(WaitForReadyStep<?>.Callback callback, Step next) {
      super(next);
      this.callback = callback;
    }

    @Override
    public StepAction onSuccess(Packet packet, KubernetesApiResponse<V> callResponse) {
      MakeRightDomainOperation makeRightDomainOperation =
              (MakeRightDomainOperation)packet.get(MAKE_RIGHT_DOMAIN_OPERATION);
      if (makeRightDomainOperation != null) {
        makeRightDomainOperation.clear();
        makeRightDomainOperation.setLiveInfo(new DomainPresenceInfo((DomainResource) callResponse.getObject()));
        makeRightDomainOperation.withExplicitRecheck().interrupt().execute();
      }
      callback.onTimeout();
      return super.onSuccess(packet, callResponse);
    }

  }

  class Callback implements Consumer<T> {
    private final Resumable resumable;
    private final AtomicInteger recheckCount = new AtomicInteger(0);

    Callback(Resumable resumable) {
      this.resumable = resumable;
    }

    @Override
    public void accept(T resource) {
      boolean shouldProcessCallback = shouldProcessCallback(resource);
      if (shouldProcessCallback) {
        proceedFromWait(resource);
      }
    }

    private void handleResourceReady(Packet packet, T resource) {
      updatePacket(packet, resource);
      if (shouldTerminateFiber(resource)) {
        packet.put(INTROSPECTOR_JOB_FAILURE_THROWABLE, createTerminationException(resource));
      }
    }

    // The resource has now either completed or failed, so we can continue processing.
    void proceedFromWait(T resource) {

      // TEST
      LOGGER.info("TEST!!! proceedFromWait for resource: " + resource);

      removeCallback(getResourceName(), this);
      resumable.resume(packet -> handleResourceReady(packet, resource));
    }

    protected void onTimeout() {

      // TEST
      LOGGER.info("TEST!!! onTimeout for resource: " + getResourceName());

      removeCallback(getResourceName(), this);
      resumable.cancel();
    }

    boolean didResumeFiber() {
      return resumable.hasResumed();
    }

    int incrementAndGetRecheckCount() {
      return recheckCount.incrementAndGet();
    }

    int getRecheckCount() {
      return recheckCount.get();
    }
  }

  // an interface to provide a hook for unit testing.
  interface NextStepFactory {
    Step createMakeDomainRightStep(WaitForReadyStep<?>.Callback callback,
                                                   DomainPresenceInfo info, Step next);
  }

}
