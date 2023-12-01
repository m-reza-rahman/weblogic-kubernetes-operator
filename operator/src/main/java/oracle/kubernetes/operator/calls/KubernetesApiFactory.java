// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.util.generic.GenericKubernetesApi;

public interface KubernetesApiFactory {
  default <ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
  KubernetesApi<ApiType, ApiListType> create(Class<ApiType> apiTypeClass, Class<ApiListType> apiListTypeClass,
                                             String apiGroup, String apiVersion, String resourcePlural) {
    return new KubernetesApiImpl<>(apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
  }
  class KubernetesApiImpl<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends GenericKubernetesApi<ApiType, ApiListType> implements KubernetesApi<ApiType, ApiListType> {
    public KubernetesApiImpl(Class<ApiType> apiTypeClass, Class<ApiListType> apiListTypeClass,
                             String apiGroup, String apiVersion, String resourcePlural) {
      super(apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural, Client.getInstance());
    }
  }

}
