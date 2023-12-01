// Copyright (c) 2018, 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.builders;

import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.apis.PolicyV1Api;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodDisruptionBudget;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.Watchable;
import io.kubernetes.client.util.generic.options.ListOptions;
import okhttp3.Call;
import oracle.kubernetes.weblogic.domain.model.ClusterResource;
import oracle.kubernetes.weblogic.domain.model.DomainResource;

import static oracle.kubernetes.utils.OperatorUtils.isNullOrEmpty;

public class WatchBuilder {
  /** Always true for watches. */
  private static final boolean WATCH = true;

  /** Ignored for watches. */
  private static final String START_LIST = null;

  private static final Boolean ALLOW_BOOKMARKS = true;
  private static final String PRETTY = null;

  private static final String RESOURCE_VERSION_MATCH_UNSET = null;
  private static final Boolean SEND_INITIAL_EVENTS_UNSET = null;

  private final ListOptions callParams = new ListOptions();

  @SuppressWarnings({"FieldMayBeFinal", "CanBeFinal"}) // Leave non-final for unit test
  private static WatchFactory factory = new WatchFactoryImpl();

  /**
   * Creates a web hook object to track service calls.
   *
   * @param namespace the namespace
   * @return the active web hook
   * @throws ApiException if there is an error on the call that sets up the web hook.
   */
  public Watchable<V1Service> createServiceWatch(String namespace) throws ApiException {
    return factory.createWatch(callParams, V1Service.class, new ListNamespacedServiceCall(namespace));
  }

  /**
   * Creates a web hook object to track pod disruption budgets.
   *
   * @param namespace the namespace
   * @return the active web hook
   * @throws ApiException if there is an error on the call that sets up the web hook.
   */
  public Watchable<V1PodDisruptionBudget> createPodDisruptionBudgetWatch(String namespace) throws ApiException {
    return factory.createWatch(callParams, V1PodDisruptionBudget.class,
        new ListPodDisruptionBudgetCall(namespace));
  }

  /**
   * Creates a web hook object to track pods.
   *
   * @param namespace the namespace
   * @return the active web hook
   * @throws ApiException if there is an error on the call that sets up the web hook.
   */
  public Watchable<V1Pod> createPodWatch(String namespace) throws ApiException {
    return factory.createWatch(
        callParams, V1Pod.class, new ListPodCall(namespace));
  }

  /**
   * Creates a web hook object to track jobs.
   *
   * @param namespace the namespace
   * @return the active web hook
   * @throws ApiException if there is an error on the call that sets up the web hook.
   */
  public Watchable<V1Job> createJobWatch(String namespace) throws ApiException {
    return factory.createWatch(
        callParams, V1Job.class, new ListJobCall(namespace));
  }

  /**
   * Creates a web hook object to track events.
   *
   * @param namespace the namespace
   * @return the active web hook
   * @throws ApiException if there is an error on the call that sets up the web hook.
   */
  public Watchable<CoreV1Event> createEventWatch(String namespace) throws ApiException {
    return factory.createWatch(
        callParams, CoreV1Event.class, new ListEventCall(namespace));
  }

  /**
   * Creates a web hook object to track changes to WebLogic domains in one namespace.
   *
   * @param namespace the namespace in which to track domains
   * @return the active web hook
   * @throws ApiException if there is an error on the call that sets up the web hook.
   */
  public Watchable<DomainResource> createDomainWatch(String namespace) throws ApiException {
    return factory.createWatch(
        callParams, DomainResource.class, new ListDomainsCall(namespace));
  }

  public Watchable<ClusterResource> createClusterWatch(String namespace) throws ApiException {
    return factory.createWatch(
        callParams, ClusterResource.class, new ListClustersCall(namespace));
  }

  /**
   * Creates a web hook object to track config map calls.
   *
   * @param namespace the namespace
   * @return the active web hook
   * @throws ApiException if there is an error on the call that sets up the web hook.
   */
  public Watchable<V1ConfigMap> createConfigMapWatch(String namespace) throws ApiException {
    return factory.createWatch(
        callParams,
        V1ConfigMap.class,
        new ListNamespacedConfigMapCall(namespace));
  }

  /**
   * Creates a web hook object to track namespace calls.
   *
   * @return the active web hook
   * @throws ApiException if there is an error on the call that sets up the web hook.
   */
  public Watchable<V1Namespace> createNamespacesWatch() throws ApiException {
    return factory.createWatch(
        callParams,
        V1Namespace.class,
        new ListNamespaceCall());
  }

  /**
   * Sets a value for the fieldSelector parameter for the call that will set up this watch. Defaults
   * to null.
   *
   * @param fieldSelector the desired value
   * @return the updated builder
   */
  public WatchBuilder withFieldSelector(String fieldSelector) {
    callParams.setFieldSelector(fieldSelector);
    return this;
  }

  public WatchBuilder withLabelSelector(String labelSelector) {
    callParams.setLabelSelector(labelSelector);
    return this;
  }

  public WatchBuilder withLabelSelectors(String... labelSelectors) {
    callParams.setLabelSelector(!isNullOrEmpty(labelSelectors) ? String.join(",", labelSelectors) : null);
    return this;
  }

  @SuppressWarnings("SameParameterValue")
  WatchBuilder withLimit(Integer limit) {
    callParams.setLimit(limit);
    return this;
  }

  public WatchBuilder withResourceVersion(String resourceVersion) {
    callParams.setResourceVersion(resourceVersion);
    return this;
  }

  public WatchBuilder withTimeoutSeconds(Integer timeoutSeconds) {
    callParams.setTimeoutSeconds(timeoutSeconds);
    return this;
  }

  public interface WatchFactory {
    <T> Watchable<T> createWatch(
        ListOptions callParams,
        Class<?> responseBodyType,
        BiFunction<ApiClient, ListOptions, Call> function)
        throws ApiException;
  }

  static class WatchFactoryImpl implements WatchFactory {
    @Override
    public <T> Watchable<T> createWatch(
        ListOptions callParams,
        Class<?> responseBodyType,
        BiFunction<ApiClient, ListOptions, Call> function)
        throws ApiException {
      try {
        return new WatchImpl<>(callParams, responseBodyType, function);
      } catch (UncheckedApiException e) {
        throw e.getCause();
      }
    }
  }

  private record ListNamespacedServiceCall(String namespace) implements BiFunction<ApiClient, ListOptions, Call> {

    @Override
    public Call apply(ApiClient client, ListOptions callParams) {
      configureClient(client);

      try {
        return new CoreV1Api(client)
            .listNamespacedServiceCall(
                namespace,
                PRETTY,
                ALLOW_BOOKMARKS,
                START_LIST,
                callParams.getFieldSelector(),
                callParams.getLabelSelector(),
                callParams.getLimit(),
                callParams.getResourceVersion(),
                RESOURCE_VERSION_MATCH_UNSET,
                SEND_INITIAL_EVENTS_UNSET,
                callParams.getTimeoutSeconds(),
                WATCH,
                null);
      } catch (ApiException e) {
        throw new UncheckedApiException(e);
      }
    }
  }

  // Specify an infinite timeout to ensure the client doesn't time out before call or watch.
  private static void configureClient(ApiClient client) {
    client.setHttpClient(client.getHttpClient().newBuilder().readTimeout(0, TimeUnit.SECONDS).build());
  }

  private record ListPodCall(String namespace) implements BiFunction<ApiClient, ListOptions, Call> {

    @Override
    public Call apply(ApiClient client, ListOptions callParams) {
      configureClient(client);

      try {
        return new CoreV1Api(client)
            .listNamespacedPodCall(
                namespace,
                PRETTY,
                ALLOW_BOOKMARKS,
                START_LIST,
                callParams.getFieldSelector(),
                callParams.getLabelSelector(),
                callParams.getLimit(),
                callParams.getResourceVersion(),
                RESOURCE_VERSION_MATCH_UNSET,
                SEND_INITIAL_EVENTS_UNSET,
                callParams.getTimeoutSeconds(),
                WATCH,
                null);
      } catch (ApiException e) {
        throw new UncheckedApiException(e);
      }
    }
  }

  private record ListJobCall(String namespace) implements BiFunction<ApiClient, ListOptions, Call> {

    @Override
    public Call apply(ApiClient client, ListOptions callParams) {
      configureClient(client);

      try {
        return new BatchV1Api(client)
            .listNamespacedJobCall(
                namespace,
                PRETTY,
                ALLOW_BOOKMARKS,
                START_LIST,
                callParams.getFieldSelector(),
                callParams.getLabelSelector(),
                callParams.getLimit(),
                callParams.getResourceVersion(),
                RESOURCE_VERSION_MATCH_UNSET,
                SEND_INITIAL_EVENTS_UNSET,
                callParams.getTimeoutSeconds(),
                WATCH,
                null);
      } catch (ApiException e) {
        throw new UncheckedApiException(e);
      }
    }
  }

  private record ListEventCall(String namespace) implements BiFunction<ApiClient, ListOptions, Call> {

    @Override
    public Call apply(ApiClient client, ListOptions callParams) {
      configureClient(client);

      try {
        return new CoreV1Api(client)
            .listNamespacedEventCall(
                namespace,
                PRETTY,
                ALLOW_BOOKMARKS,
                START_LIST,
                callParams.getFieldSelector(),
                callParams.getLabelSelector(),
                callParams.getLimit(),
                callParams.getResourceVersion(),
                RESOURCE_VERSION_MATCH_UNSET,
                SEND_INITIAL_EVENTS_UNSET,
                callParams.getTimeoutSeconds(),
                WATCH,
                null);
      } catch (ApiException e) {
        throw new UncheckedApiException(e);
      }
    }
  }

  private record ListPodDisruptionBudgetCall(String namespace) implements BiFunction<ApiClient, ListOptions, Call> {

    @Override
    public Call apply(ApiClient client, ListOptions callParams) {
      configureClient(client);

      try {
        return new PolicyV1Api(client)
            .listNamespacedPodDisruptionBudgetCall(
                namespace,
                PRETTY,
                ALLOW_BOOKMARKS,
                START_LIST,
                callParams.getFieldSelector(),
                callParams.getLabelSelector(),
                callParams.getLimit(),
                callParams.getResourceVersion(),
                RESOURCE_VERSION_MATCH_UNSET,
                SEND_INITIAL_EVENTS_UNSET,
                callParams.getTimeoutSeconds(),
                WATCH,
                null);
      } catch (ApiException e) {
        throw new UncheckedApiException(e);
      }
    }
  }

  private record ListClustersCall(String namespace) implements BiFunction<ApiClient, ListOptions, Call> {

    @Override
    public Call apply(ApiClient client, ListOptions callParams) {
      configureClient(client);

      try {
        return new CustomObjectsApi(client)
            .listNamespacedCustomObjectCall(
                "weblogic.oracle",
                "v1",
                namespace,
                "clusters",
                PRETTY,
                ALLOW_BOOKMARKS,
                START_LIST,
                callParams.getFieldSelector(),
                callParams.getLabelSelector(),
                callParams.getLimit(),
                callParams.getResourceVersion(),
                null,
                callParams.getTimeoutSeconds(),
                WATCH,
                null);
      } catch (ApiException e) {
        throw new UncheckedApiException(e);
      }
    }
  }

  private record ListDomainsCall(String namespace) implements BiFunction<ApiClient, ListOptions, Call> {

    @Override
    public Call apply(ApiClient client, ListOptions callParams) {
      configureClient(client);

      try {
        return new CustomObjectsApi(client)
            .listNamespacedCustomObjectCall(
                "weblogic.oracle",
                "v9",
                namespace,
                "domains",
                PRETTY,
                ALLOW_BOOKMARKS,
                START_LIST,
                callParams.getFieldSelector(),
                callParams.getLabelSelector(),
                callParams.getLimit(),
                callParams.getResourceVersion(),
                null,
                callParams.getTimeoutSeconds(),
                WATCH,
                null);
      } catch (ApiException e) {
        throw new UncheckedApiException(e);
      }
    }
  }

  private record ListNamespacedConfigMapCall(String namespace) implements BiFunction<ApiClient, ListOptions, Call> {

    @Override
    public Call apply(ApiClient client, ListOptions callParams) {
      configureClient(client);

      try {
        return new CoreV1Api(client)
            .listNamespacedConfigMapCall(
                namespace,
                PRETTY,
                ALLOW_BOOKMARKS,
                START_LIST,
                callParams.getFieldSelector(),
                callParams.getLabelSelector(),
                callParams.getLimit(),
                callParams.getResourceVersion(),
                RESOURCE_VERSION_MATCH_UNSET,
                SEND_INITIAL_EVENTS_UNSET,
                callParams.getTimeoutSeconds(),
                WATCH,
                null);
      } catch (ApiException e) {
        throw new UncheckedApiException(e);
      }
    }
  }

  private static class ListNamespaceCall implements BiFunction<ApiClient, ListOptions, Call> {

    @Override
    public Call apply(ApiClient client, ListOptions callParams) {
      configureClient(client);

      try {
        return new CoreV1Api(client)
            .listNamespaceCall(
                PRETTY,
                ALLOW_BOOKMARKS,
                START_LIST,
                callParams.getFieldSelector(),
                callParams.getLabelSelector(),
                callParams.getLimit(),
                callParams.getResourceVersion(),
                RESOURCE_VERSION_MATCH_UNSET,
                SEND_INITIAL_EVENTS_UNSET,
                callParams.getTimeoutSeconds(),
                WATCH,
                null);
      } catch (ApiException e) {
        throw new UncheckedApiException(e);
      }
    }
  }

}
