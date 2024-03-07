// Copyright (c) 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.util.function.UnaryOperator;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.VersionApi;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import io.kubernetes.client.util.generic.options.DeleteOptions;
import io.kubernetes.client.util.generic.options.ListOptions;

public interface KubernetesApiFactory {
  default <A extends KubernetesObject, L extends KubernetesListObject>
      KubernetesApi<A, L> create(Class<A> apiTypeClass, Class<L> apiListTypeClass,
                                 String apiGroup, String apiVersion, String resourcePlural,
                                 UnaryOperator<ApiClient> clientSelector) {
    return new KubernetesApiImpl<>(apiTypeClass, apiListTypeClass, apiGroup, apiVersion,
            resourcePlural, clientSelector);
  }

  class KubernetesApiImpl<A extends KubernetesObject, L extends KubernetesListObject>
      extends GenericKubernetesApi<A, L> implements KubernetesApi<A, L> {
    public KubernetesApiImpl(Class<A> apiTypeClass, Class<L> apiListTypeClass,
                             String apiGroup, String apiVersion, String resourcePlural,
                             UnaryOperator<ApiClient> clientSelector) {
      super(apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
              clientSelector.apply(Client.getInstance()));
    }

    @Override
    public KubernetesApiResponse<RequestBuilder.V1StatusObject> deleteCollection(
        String namespace, ListOptions listOptions, DeleteOptions deleteOptions) {
      CoreV1Api c = new CoreV1Api(Client.getInstance());
      try {
        CoreV1Api.APIdeleteCollectionNamespacedPodRequest request =
                c.deleteCollectionNamespacedPod(namespace)
                        .fieldSelector(listOptions.getFieldSelector()).labelSelector(listOptions.getLabelSelector());
        if (deleteOptions != null) {
          Long gracePeriodSeconds = deleteOptions.getGracePeriodSeconds();
          if (gracePeriodSeconds != null) {
            request = request.gracePeriodSeconds(gracePeriodSeconds.intValue());
          }
          Boolean orphanDependents = deleteOptions.getOrphanDependents();
          if (orphanDependents != null) {
            request = request.orphanDependents(orphanDependents);
          }
          String propagationPolicy = deleteOptions.getPropagationPolicy();
          if (propagationPolicy != null) {
            request = request.propagationPolicy(propagationPolicy);
          }
        }
        return new KubernetesApiResponse<>(new RequestBuilder.V1StatusObject(
            request.execute()));
      } catch (ApiException e) {
        return RequestStep.responseFromApiException(c.getApiClient(), e);
      }
    }

    @Override
    public KubernetesApiResponse<RequestBuilder.StringObject> logs(String namespace, String name, String container) {
      CoreV1Api c = new CoreV1Api(Client.getInstance());
      try {
        return new KubernetesApiResponse<>(new RequestBuilder.StringObject(
            c.readNamespacedPodLog(name, namespace).container(container).execute()));
      } catch (ApiException e) {
        return RequestStep.responseFromApiException(c.getApiClient(), e);
      }
    }

    @Override
    public KubernetesApiResponse<RequestBuilder.VersionInfoObject> getVersionCode() {
      VersionApi c = new VersionApi(Client.getInstance());
      try {
        return new KubernetesApiResponse<>(new RequestBuilder.VersionInfoObject(c.getCode().execute()));
      } catch (ApiException e) {
        return RequestStep.responseFromApiException(c.getApiClient(), e);
      }
    }
  }

}
