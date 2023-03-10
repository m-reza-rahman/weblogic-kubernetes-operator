// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;

@FunctionalInterface
public interface Request<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject> {
  KubernetesApiResponse<ApiType> execute(GenericKubernetesApi<ApiType, ApiListType> client);
}
