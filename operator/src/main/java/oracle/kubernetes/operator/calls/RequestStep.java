// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import oracle.kubernetes.common.logging.MessageKeys;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.ThreadLoggingContext;
import oracle.kubernetes.operator.work.AsyncFiber;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

import static oracle.kubernetes.common.logging.MessageKeys.ASYNC_SUCCESS;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_CONFLICT;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_GATEWAY_TIMEOUT;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_INTERNAL_ERROR;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_NOT_FOUND;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_TOO_MANY_REQUESTS;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_UNAVAILABLE;
import static oracle.kubernetes.operator.logging.ThreadLoggingContext.setThreadContext;

/**
 * A Step driven by a call to the Kubernetes API.
 */
public class RequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject> extends Step {
  public static final String RESPONSE_COMPONENT_NAME = "response";
  public static final String CONTINUE = "continue";
  public static final int FIBER_TIMEOUT = 0;

  private static final Random R = new Random();
  private static final int HIGH = 200;
  private static final int LOW = 10;
  private static final int SCALE = 100;
  private static final int MAX = 10000;
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private final Class<ApiType> apiTypeClass;
  private final Class<ApiListType> apiListTypeClass;
  private final String apiGroup;
  private final String apiVersion;
  private final String resourcePlural;
  private final Request<ApiType, ApiListType> request; // FIXME?

  /**
   * Construct request step.
   *
   * @param next Response step
   * @param apiTypeClass API type class
   * @param apiListTypeClass API list type class
   * @param apiGroup API group
   * @param apiVersion API version
   * @param resourcePlural Resource plural
   * @param request Request function
   */
  public RequestStep(
          ResponseStep<ApiType> next, // FIXME?
          Class<ApiType> apiTypeClass,
          Class<ApiListType> apiListTypeClass,
          String apiGroup,
          String apiVersion,
          String resourcePlural,
          Request<ApiType, ApiListType> request) {
    super(next);
    this.apiGroup = apiGroup;
    this.apiVersion = apiVersion;
    this.resourcePlural = resourcePlural;
    this.apiTypeClass = apiTypeClass;
    this.apiListTypeClass = apiListTypeClass;
    this.request = request;

    next.setPrevious(this);
  }

  /**
   * Access continue field, if any, from list metadata.
   * @param result Kubernetes list result
   * @return Continue value
   */
  public static String accessContinue(Object result) {
    return Optional.ofNullable(result)
        .filter(KubernetesListObject.class::isInstance)
        .map(KubernetesListObject.class::cast)
        .map(KubernetesListObject::getMetadata)
        .map(V1ListMeta::getContinue)
        .filter(Predicate.not(String::isEmpty))
        .orElse(null);
  }

  @Override
  public Void apply(Packet packet) {
    // clear out earlier results
    String cont = (String) packet.remove(CONTINUE);
    Component oldResponse = packet.getComponents().remove(RESPONSE_COMPONENT_NAME);
    if (oldResponse != null) {
      @SuppressWarnings("unchecked")
      KubernetesApiResponse<?> old = oldResponse.getSpi(KubernetesApiResponse.class);
      if (cont != null && old != null && old.getObject() != null) {
        // called again, access continue value, if available
        cont = accessContinue(old.getObject());
      }
    }

    // FIXME: if cont set and it's a list, then use the value
    GenericKubernetesApi<ApiType, ApiListType> client = new GenericKubernetesApi<>(
        apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural, Client.getInstance());
    KubernetesApiResponse<ApiType> result = request.execute(client);

    // update packet
    packet.getComponents().put(RESPONSE_COMPONENT_NAME, Component.createFor(result));

    return doNext(packet);
  }

  // Schedule the timeout check to happen on the fiber at some number of seconds in the future.
  private void scheduleTimeoutCheck(AsyncFiber fiber, int timeoutSeconds, Runnable timeoutCheck) {
    fiber.scheduleOnce(timeoutSeconds, TimeUnit.SECONDS, timeoutCheck);
  }

  private final class DefaultRetryStrategy implements RetryStrategy {
    private long retryCount = 0;
    private final int maxRetryCount;
    private final Step retryStep;
    DefaultRetryStrategy(int maxRetryCount, Step retryStep) {
      this.maxRetryCount = maxRetryCount;
      this.retryStep = retryStep;
    }

    @Override
    public Void doPotentialRetry(Step conflictStep, Packet packet, int statusCode) {
      if (mayRetryOnStatusValue(statusCode)) {
        optionallyAdjustListenTimeout(statusCode);
        return retriesLeft() ? backOffAndRetry(packet, retryStep) : null;
      } else if (isRestartableConflict(conflictStep, statusCode)) {
        return backOffAndRetry(packet, conflictStep);
      } else {
        return null;
      }
    }

    private void optionallyAdjustListenTimeout(int statusCode) {
      if (statusCode == FIBER_TIMEOUT || statusCode == HTTP_GATEWAY_TIMEOUT) {
        listener.listenTimeoutDoubled();
      }
    }

    // Check statusCode, many statuses should not be retried
    // https://github.com/kubernetes/community/blob/master/contributors/devel/sig-architecture/api-conventions.md#http-status-codes
    private boolean mayRetryOnStatusValue(int statusCode) {
      return statusCode == FIBER_TIMEOUT
            || statusCode == HTTP_TOO_MANY_REQUESTS
            || statusCode == HTTP_INTERNAL_ERROR
            || statusCode == HTTP_UNAVAILABLE
            || statusCode == HTTP_GATEWAY_TIMEOUT;
    }

    @Nonnull
    private Void backOffAndRetry(Packet packet, Step nextStep) {
      final long waitTime = getNextWaitTime();

      final Void na = new Void();
      na.delay(nextStep, packet, waitTime, TimeUnit.MILLISECONDS);
      return na;
    }

    // Compute wait time, increasing exponentially
    private int getNextWaitTime() {
      return Math.min((2 << ++retryCount) * SCALE, MAX) + (R.nextInt(HIGH - LOW) + LOW);
    }

    // Conflict is an optimistic locking failure.  Therefore, we can't
    // simply retry the request.  Instead, application code needs to rebuild
    // the request based on latest contents.  If provided, a conflict step will do that.
    private boolean isRestartableConflict(Step conflictStep, int statusCode) {
      return statusCode == HTTP_CONFLICT && conflictStep != null;
    }

    private boolean retriesLeft() {
      return (retryCount + 1) <= maxRetryCount;
    }

    @Override
    public void reset() {
      retryCount = 0;
    }
  }
}
