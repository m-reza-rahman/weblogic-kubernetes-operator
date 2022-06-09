// Copyright (c) 2017, 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiCallback;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import okhttp3.Call;
import oracle.kubernetes.weblogic.domain.model.ClusterList;
import oracle.kubernetes.weblogic.domain.model.DomainList;
import oracle.kubernetes.weblogic.domain.model.DomainResource;

import static oracle.kubernetes.operator.KubernetesConstants.CLUSTER_PLURAL;
import static oracle.kubernetes.operator.KubernetesConstants.CLUSTER_VERSION;
import static oracle.kubernetes.operator.KubernetesConstants.DOMAIN_GROUP;
import static oracle.kubernetes.operator.KubernetesConstants.DOMAIN_PLURAL;
import static oracle.kubernetes.operator.KubernetesConstants.DOMAIN_VERSION;

public class WeblogicApi extends CustomObjectsApi {

  public WeblogicApi(ApiClient apiClient) {
    super(apiClient);
  }

  /**
   * Asynchronously read domain.
   *
   * @param name      name
   * @param namespace namespace
   * @param callback  callback
   * @return call
   * @throws ApiException on failure
   */
  public Call getNamespacedDomainAsync(String name, String namespace, ApiCallback<DomainResource> callback)
      throws ApiException {
    return getNamespacedCustomObjectAsync(DOMAIN_GROUP, DOMAIN_VERSION, namespace,
        DOMAIN_PLURAL, name, wrapForDomain(callback));
  }

  /**
   * Generate call to list domains.
   *
   * @param namespace       namespace
   * @param pretty          pretty flag
   * @param cont            continuation
   * @param fieldSelector   field selector
   * @param labelSelector   label selector
   * @param limit           limit
   * @param resourceVersion resource version
   * @param timeoutSeconds  timeout
   * @param watch           if watch
   * @param callback        callback
   * @return call
   * @throws ApiException on failure
   */
  public Call listNamespacedDomainCall(
      String namespace,
      String pretty,
      String cont,
      String fieldSelector,
      String labelSelector,
      Integer limit,
      String resourceVersion,
      Integer timeoutSeconds,
      Boolean watch,
      ApiCallback<DomainList> callback)
      throws ApiException {
    return listNamespacedCustomObjectCall(DOMAIN_GROUP, DOMAIN_VERSION, namespace, DOMAIN_PLURAL,
        pretty, null, cont, fieldSelector, labelSelector, limit, resourceVersion, null,
        timeoutSeconds, watch, wrapForDomainList(callback));
  }

  /**
   * List domains.
   *
   * @param namespace       namespace
   * @param pretty          pretty flag
   * @param cont            continuation
   * @param fieldSelector   field selector
   * @param labelSelector   label selector
   * @param limit           limit
   * @param resourceVersion resource version
   * @param timeoutSeconds  timeout
   * @param watch           if watch
   * @return domain list
   * @throws ApiException on failure
   */
  public DomainList listNamespacedDomain(
      String namespace,
      String pretty,
      String cont,
      String fieldSelector,
      String labelSelector,
      Integer limit,
      String resourceVersion,
      Integer timeoutSeconds,
      Boolean watch)
      throws ApiException {
    return toDomainList(listNamespacedCustomObject(DOMAIN_GROUP, DOMAIN_VERSION, namespace, DOMAIN_PLURAL, pretty,
        null, cont, fieldSelector, labelSelector, limit, resourceVersion, null,
        timeoutSeconds, watch));
  }

  /**
   * Asynchronously list clusters.
   *
   * @param namespace       namespace
   * @param cont            continuation
   * @param fieldSelector   field selector
   * @param labelSelector   label selector
   * @param limit           limit
   * @param timeoutSeconds  timeout
   * @param callback        callback
   * @return call
   * @throws ApiException on failure
   */
  public Call listNamespacedClusterAsync(
      String namespace,
      String cont,
      String fieldSelector,
      String labelSelector,
      Integer limit,
      Integer timeoutSeconds,
      ApiCallback<ClusterList> callback)
      throws ApiException {
    return listNamespacedCustomObjectAsync(DOMAIN_GROUP, CLUSTER_VERSION, namespace, CLUSTER_PLURAL,
        null, null, cont, fieldSelector, labelSelector, limit, "", null,
        timeoutSeconds, null, wrapForClusterList(callback));
  }

  /**
   * Asynchronously list domains.
   *
   * @param namespace       namespace
   * @param pretty          pretty flag
   * @param cont            continuation
   * @param fieldSelector   field selector
   * @param labelSelector   label selector
   * @param limit           limit
   * @param resourceVersion resource version
   * @param timeoutSeconds  timeout
   * @param watch           watch
   * @param callback        callback
   * @return call
   * @throws ApiException on failure
   */
  public Call listNamespacedDomainAsync(
      String namespace,
      String pretty,
      String cont,
      String fieldSelector,
      String labelSelector,
      Integer limit,
      String resourceVersion,
      Integer timeoutSeconds,
      Boolean watch,
      ApiCallback<DomainList> callback)
      throws ApiException {
    return listNamespacedCustomObjectAsync(DOMAIN_GROUP, DOMAIN_VERSION, namespace, DOMAIN_PLURAL,
        pretty, null, cont, fieldSelector, labelSelector, limit, resourceVersion, null,
        timeoutSeconds, watch, wrapForDomainList(callback));
  }

  /**
   * Patch domain.
   *
   * @param name      name
   * @param namespace namespace
   * @param body      patch
   * @return domain
   * @throws ApiException on failure
   */
  public DomainResource patchNamespacedDomain(String name, String namespace, V1Patch body)
      throws ApiException {
    return toDomain(patchNamespacedCustomObject(DOMAIN_GROUP, DOMAIN_VERSION, namespace, DOMAIN_PLURAL,
        name, body, null, null, null));
  }

  /**
   * Asynchronously patch domain.
   *
   * @param name      name
   * @param namespace namespace
   * @param body      patch
   * @param callback  callback
   * @return call
   * @throws ApiException on failure
   */
  public Call patchNamespacedDomainAsync(
      String name, String namespace, V1Patch body, ApiCallback<DomainResource> callback)
      throws ApiException {
    return patchNamespacedCustomObjectAsync(DOMAIN_GROUP, DOMAIN_VERSION, namespace, DOMAIN_PLURAL,
        name, body, null, null, null, wrapForDomain(callback));
  }

  /**
   * Replace domain.
   *
   * @param name      name
   * @param namespace namespace
   * @param body      domain
   * @return domain
   * @throws ApiException on failure
   */
  public DomainResource replaceNamespacedDomain(String name, String namespace, DomainResource body)
      throws ApiException {
    return toDomain(replaceNamespacedCustomObject(DOMAIN_GROUP, DOMAIN_VERSION, namespace, DOMAIN_PLURAL,
        name, body, null, null));
  }

  /**
   * Asynchronously replace domain.
   *
   * @param name      name
   * @param namespace namespace
   * @param body      domain
   * @param callback  callback
   * @return call
   * @throws ApiException on failure
   */
  public Call replaceNamespacedDomainAsync(
      String name, String namespace, DomainResource body, ApiCallback<DomainResource> callback)
      throws ApiException {
    return replaceNamespacedCustomObjectAsync(DOMAIN_GROUP, DOMAIN_VERSION, namespace, DOMAIN_PLURAL,
        name, body, null, null, wrapForDomain(callback));
  }

  /**
   * Replace domain status.
   *
   * @param name      name
   * @param namespace namespace
   * @param body      domain
   * @return domain
   * @throws ApiException on failure
   */
  public DomainResource replaceNamespacedDomainStatus(String name, String namespace, DomainResource body)
      throws ApiException {
    return toDomain(replaceNamespacedCustomObjectStatus(DOMAIN_GROUP, DOMAIN_VERSION, namespace, DOMAIN_PLURAL,
        name, body, null, null));
  }

  /**
   * Asynchronously replace domain status.
   *
   * @param name      name
   * @param namespace namespace
   * @param body      domain
   * @param callback  callback
   * @return call
   * @throws ApiException on failure
   */
  public Call replaceNamespacedDomainStatusAsync(
      String name, String namespace, DomainResource body, ApiCallback<DomainResource> callback)
      throws ApiException {

    return replaceNamespacedCustomObjectStatusAsync(DOMAIN_GROUP, DOMAIN_VERSION, namespace, DOMAIN_PLURAL,
        name, body, null, null, wrapForDomain(callback));
  }

  private ApiCallback<Object> wrapForDomain(ApiCallback<DomainResource> inner) {
    return Optional.ofNullable(inner).map(DomainApiCallbackWrapper::new).orElse(null);
  }

  private class DomainApiCallbackWrapper implements ApiCallback<Object> {
    private final ApiCallback<DomainResource> domainApiCallback;

    public DomainApiCallbackWrapper(ApiCallback<DomainResource> domainApiCallback) {
      this.domainApiCallback = domainApiCallback;
    }

    @Override
    public void onFailure(ApiException e, int i, Map<String, List<String>> map) {
      domainApiCallback.onFailure(e, i, map);
    }

    @Override
    public void onSuccess(Object o, int i, Map<String, List<String>> map) {
      domainApiCallback.onSuccess(toDomain(o), i, map);
    }

    @Override
    public void onUploadProgress(long l, long l1, boolean b) {
      domainApiCallback.onUploadProgress(l, l1, b);
    }

    @Override
    public void onDownloadProgress(long l, long l1, boolean b) {
      domainApiCallback.onDownloadProgress(l, l1, b);
    }
  }

  private ApiCallback<Object> wrapForClusterList(ApiCallback<ClusterList> inner) {
    return Optional.ofNullable(inner).map(ClusterListApiCallbackWrapper::new).orElse(null);
  }

  private ApiCallback<Object> wrapForDomainList(ApiCallback<DomainList> inner) {
    return Optional.ofNullable(inner).map(DomainListApiCallbackWrapper::new).orElse(null);
  }

  private class ClusterListApiCallbackWrapper implements ApiCallback<Object> {
    private final ApiCallback<ClusterList> clusterListApiCallback;

    public ClusterListApiCallbackWrapper(ApiCallback<ClusterList> clusterListApiCallback) {
      this.clusterListApiCallback = clusterListApiCallback;
    }

    @Override
    public void onFailure(ApiException e, int i, Map<String, List<String>> map) {
      clusterListApiCallback.onFailure(e, i, map);
    }

    @Override
    public void onSuccess(Object o, int i, Map<String, List<String>> map) {
      clusterListApiCallback.onSuccess(toClusterList(o), i, map);
    }

    @Override
    public void onUploadProgress(long l, long l1, boolean b) {
      clusterListApiCallback.onUploadProgress(l, l1, b);
    }

    @Override
    public void onDownloadProgress(long l, long l1, boolean b) {
      clusterListApiCallback.onDownloadProgress(l, l1, b);
    }

    private ClusterList toClusterList(Object o) {
      if (o == null) {
        return null;
      }
      return getApiClient().getJSON().getGson().fromJson(convertToJson(o), ClusterList.class);
    }
  }

  private class DomainListApiCallbackWrapper implements ApiCallback<Object> {
    private final ApiCallback<DomainList> domainListApiCallback;

    public DomainListApiCallbackWrapper(ApiCallback<DomainList> domainListApiCallback) {
      this.domainListApiCallback = domainListApiCallback;
    }

    @Override
    public void onFailure(ApiException e, int i, Map<String, List<String>> map) {
      domainListApiCallback.onFailure(e, i, map);
    }

    @Override
    public void onSuccess(Object o, int i, Map<String, List<String>> map) {
      domainListApiCallback.onSuccess(toDomainList(o), i, map);
    }

    @Override
    public void onUploadProgress(long l, long l1, boolean b) {
      domainListApiCallback.onUploadProgress(l, l1, b);
    }

    @Override
    public void onDownloadProgress(long l, long l1, boolean b) {
      domainListApiCallback.onDownloadProgress(l, l1, b);
    }
  }

  private JsonElement convertToJson(Object o) {
    Gson gson = getApiClient().getJSON().getGson();
    return gson.toJsonTree(o);
  }

  private DomainResource toDomain(Object o) {
    if (o == null) {
      return null;
    }
    return getApiClient().getJSON().getGson().fromJson(convertToJson(o), DomainResource.class);
  }

  private DomainList toDomainList(Object o) {
    if (o == null) {
      return null;
    }
    return getApiClient().getJSON().getGson().fromJson(convertToJson(o), DomainList.class);
  }
}
