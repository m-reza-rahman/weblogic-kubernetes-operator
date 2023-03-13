// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import oracle.kubernetes.operator.work.AsyncFiber;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

/**
 * A Step driven by a call to the Kubernetes API.
 */
public class RequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject> extends Step {
  public static final String RESPONSE_COMPONENT_NAME = "response";
  public static final String CONTINUE = "continue";
  public static final int FIBER_TIMEOUT = 0;

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
    KubernetesApiResponse oldResponse = (KubernetesApiResponse) packet.remove(RESPONSE_COMPONENT_NAME);
    if (oldResponse != null && oldResponse.getObject() != null) {
      // called again, access continue value, if available
      cont = accessContinue(oldResponse.getObject());
    }

    // FIXME: if cont set and it's a list, then use the value
    GenericKubernetesApi<ApiType, ApiListType> client = new GenericKubernetesApi<>(
        apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural, Client.getInstance());
    KubernetesApiResponse<ApiType> result = request.execute(client);

    // update packet
    packet.put(RESPONSE_COMPONENT_NAME, result);

    return doNext(packet);
  }

  // Schedule the timeout check to happen on the fiber at some number of seconds in the future.
  private void scheduleTimeoutCheck(AsyncFiber fiber, int timeoutSeconds, Runnable timeoutCheck) {
    fiber.scheduleOnce(timeoutSeconds, TimeUnit.SECONDS, timeoutCheck);
  }
}
