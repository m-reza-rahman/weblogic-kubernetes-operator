// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.util.generic.GenericKubernetesApi;

public interface KubernetesApiFactory {
  default <A extends KubernetesObject, L extends KubernetesListObject>
      KubernetesApi<A, L> create(Class<A> apiTypeClass, Class<L> apiListTypeClass,
                                 String apiGroup, String apiVersion, String resourcePlural) {
    return new KubernetesApiImpl<>(apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
  }

  class KubernetesApiImpl<A extends KubernetesObject, L extends KubernetesListObject>
      extends GenericKubernetesApi<A, L> implements KubernetesApi<A, L> {
    public KubernetesApiImpl(Class<A> apiTypeClass, Class<L> apiListTypeClass,
                             String apiGroup, String apiVersion, String resourcePlural) {
      super(apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural, Client.getInstance());
    }
  }

}
