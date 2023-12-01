// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import io.kubernetes.client.util.generic.options.CreateOptions;
import io.kubernetes.client.util.generic.options.DeleteOptions;
import io.kubernetes.client.util.generic.options.GetOptions;
import io.kubernetes.client.util.generic.options.ListOptions;
import io.kubernetes.client.util.generic.options.PatchOptions;
import io.kubernetes.client.util.generic.options.UpdateOptions;

import java.util.function.Function;

public interface KubernetesApi<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject> {

  /**
   * Get kubernetes api response.
   *
   * @param name the name
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiType> get(String name) {
    return get(name, new GetOptions());
  }

  /**
   * Get kubernetes api response.
   *
   * @param name the name
   * @param getOptions the get options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> get(String name, final GetOptions getOptions);

  /**
   * Get kubernetes api response under the namespace.
   *
   * @param namespace the namespace
   * @param name the name
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiType> get(String namespace, String name) {
    return get(namespace, name, new GetOptions());
  }

  /**
   * Get kubernetes api response.
   *
   * @param namespace the namespace
   * @param name the name
   * @param getOptions the get options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> get(String namespace, String name, final GetOptions getOptions);


  /**
   * List kubernetes api response cluster-scoped.
   *
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiListType> list() {
    return list(new ListOptions());
  }

  /**
   * List kubernetes api response.
   *
   * @param listOptions the list options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiListType> list(final ListOptions listOptions);

  /**
   * List kubernetes api response under the namespace.
   *
   * @param namespace the namespace
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiListType> list(String namespace) {
    return list(namespace, new ListOptions());
  }

  /**
   * List kubernetes api response.
   *
   * @param namespace the namespace
   * @param listOptions the list options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiListType> list(String namespace, final ListOptions listOptions);

  /**
   * Create kubernetes api response, if the namespace in the object is present, it will send a
   * namespace-scoped requests, vice versa.
   *
   * @param object the object
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiType> create(ApiType object) {
    return create(object, new CreateOptions());
  }

  /**
   * Create kubernetes api response.
   *
   * @param object the object
   * @param createOptions the create options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> create(ApiType object, final CreateOptions createOptions);

  /**
   * Create kubernetes api response.
   *
   * @param namespace the namespace
   * @param object the object
   * @param createOptions the create options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> create(
      String namespace, ApiType object, final CreateOptions createOptions);

  /**
   * Create kubernetes api response, if the namespace in the object is present, it will send a
   * namespace-scoped requests, vice versa.
   *
   * @param object the object
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiType> update(ApiType object) {
    return update(object, new UpdateOptions());
  }

  /**
   * Update kubernetes api response.
   *
   * @param object the object
   * @param updateOptions the update options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> update(ApiType object, final UpdateOptions updateOptions);

  /**
   * Create kubernetes api response, if the namespace in the object is present, it will send a
   * namespace-scoped requests, vice versa.
   *
   * @param object the object
   * @param status function to extract the status from the object
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiType> updateStatus(
      ApiType object, Function<ApiType, Object> status) {
    return updateStatus(object, status, new UpdateOptions());
  }

  /**
   * Update status of kubernetes api response.
   *
   * @param object the object
   * @param status function to extract the status from the object
   * @param updateOptions the update options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> updateStatus(
      ApiType object, Function<ApiType, Object> status, final UpdateOptions updateOptions);

  /**
   * Patch kubernetes api response.
   *
   * @param name the name
   * @param patchType the patch type, supported values defined in V1Patch
   * @param patch the string patch content
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiType> patch(String name, String patchType, V1Patch patch) {
    return patch(name, patchType, patch, new PatchOptions());
  }

  /**
   * Patch kubernetes api response.
   *
   * @param name the name
   * @param patchType the patch type
   * @param patch the patch
   * @param patchOptions the patch options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> patch(
      String name, String patchType, V1Patch patch, final PatchOptions patchOptions);

  /**
   * Patch kubernetes api response under the namespace.
   *
   * @param namespace the namespace
   * @param name the name
   * @param patchType the patch type, supported values defined in V1Patch
   * @param patch the string patch content
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiType> patch(
      String namespace, String name, String patchType, V1Patch patch) {
    return patch(namespace, name, patchType, patch, new PatchOptions());
  }

  /**
   * Patch kubernetes api response.
   *
   * @param namespace the namespace
   * @param name the name
   * @param patchType the patch type
   * @param patch the patch
   * @param patchOptions the patch options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> patch(
      String namespace,
      String name,
      String patchType,
      V1Patch patch,
      final PatchOptions patchOptions);

  /**
   * Delete kubernetes api response.
   *
   * @param name the name
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiType> delete(String name) {
    return delete(name, new DeleteOptions());
  }

  /**
   * Delete kubernetes api response.
   *
   * @param name the name
   * @param deleteOptions the delete options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> delete(String name, final DeleteOptions deleteOptions);

  /**
   * Delete kubernetes api response under the namespace.
   *
   * @param namespace the namespace
   * @param name the name
   * @return the kubernetes api response
   */
  default KubernetesApiResponse<ApiType> delete(String namespace, String name) {
    return delete(namespace, name, new DeleteOptions());
  }

  /**
   * Delete kubernetes api response.
   *
   * @param namespace the namespace
   * @param name the name
   * @param deleteOptions the delete options
   * @return the kubernetes api response
   */
  KubernetesApiResponse<ApiType> delete(
      String namespace, String name, final DeleteOptions deleteOptions);

}
