// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;

@FunctionalInterface
public interface Request<T> {
  T execute(RequestParams requestParams, ApiClient client, String cont) throws ApiException;
}
