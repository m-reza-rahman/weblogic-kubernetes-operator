// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import io.kubernetes.client.util.generic.options.CreateOptions;
import oracle.kubernetes.operator.work.Step;

public class RequestBuilder<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject> {
  private final Class<ApiType> apiTypeClass;
  private final Class<ApiListType> apiListTypeClass;
  private final String apiGroup;
  private final String apiVersion;
  private final String resourcePlural;

  public RequestBuilder(
      Class<ApiType> apiTypeClass,
      Class<ApiListType> apiListTypeClass,
      String apiGroup,
      String apiVersion,
      String resourcePlural) {
    this.apiGroup = apiGroup;
    this.apiVersion = apiVersion;
    this.resourcePlural = resourcePlural;
    this.apiTypeClass = apiTypeClass;
    this.apiListTypeClass = apiListTypeClass;
  }

  public Step create(ApiType object, ResponseStep<ApiType> responseStep) {
    return create(object, new CreateOptions(), responseStep);
  }

  public Step create(ApiType object, final CreateOptions createOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(responseStep); // FIXME
  }
}
