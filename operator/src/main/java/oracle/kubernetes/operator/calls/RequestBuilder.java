// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.util.function.Function;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.common.KubernetesType;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.CoreV1EventList;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1CustomResourceDefinition;
import io.kubernetes.client.openapi.models.V1CustomResourceDefinitionList;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodDisruptionBudget;
import io.kubernetes.client.openapi.models.V1PodDisruptionBudgetList;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.openapi.models.V1SelfSubjectRulesReview;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.openapi.models.V1Status;
import io.kubernetes.client.openapi.models.V1SubjectAccessReview;
import io.kubernetes.client.openapi.models.V1TokenReview;
import io.kubernetes.client.openapi.models.V1ValidatingWebhookConfiguration;
import io.kubernetes.client.openapi.models.V1ValidatingWebhookConfigurationList;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import io.kubernetes.client.util.generic.options.CreateOptions;
import io.kubernetes.client.util.generic.options.DeleteOptions;
import io.kubernetes.client.util.generic.options.GetOptions;
import io.kubernetes.client.util.generic.options.ListOptions;
import io.kubernetes.client.util.generic.options.PatchOptions;
import io.kubernetes.client.util.generic.options.UpdateOptions;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.weblogic.domain.model.ClusterList;
import oracle.kubernetes.weblogic.domain.model.ClusterResource;
import oracle.kubernetes.weblogic.domain.model.DomainList;
import oracle.kubernetes.weblogic.domain.model.DomainResource;

public class RequestBuilder<A extends KubernetesObject, L extends KubernetesListObject> {
  public static final RequestBuilder<DomainResource, DomainList> DOMAIN =
      new RequestBuilder<>(DomainResource.class, DomainList.class, "weblogic.oracle", "v9", "domains");
  public static final RequestBuilder<ClusterResource, ClusterList> CLUSTER =
      new RequestBuilder<>(ClusterResource.class, ClusterList.class, "weblogic.oracle", "v1", "clusters");

  public static final RequestBuilder<V1Namespace, V1NamespaceList> NAMESPACE =
      new RequestBuilder<>(V1Namespace.class, V1NamespaceList.class, "", "v1", "namespaces");
  public static final PodRequestBuilder POD = new PodRequestBuilder();
  public static final RequestBuilder<V1Service, V1ServiceList> SERVICE =
      new RequestBuilder<>(V1Service.class, V1ServiceList.class, "", "v1", "services");
  public static final RequestBuilder<V1ConfigMap, V1ConfigMapList> CM =
      new RequestBuilder<>(V1ConfigMap.class, V1ConfigMapList.class, "", "v1", "configmaps");
  public static final RequestBuilder<V1Secret, V1SecretList> SECRET =
      new RequestBuilder<>(V1Secret.class, V1SecretList.class, "", "v1", "secrets");
  public static final RequestBuilder<CoreV1Event, CoreV1EventList> EVENT =
      new RequestBuilder<>(CoreV1Event.class, CoreV1EventList.class, "", "v1", "events");

  public static final RequestBuilder<V1CustomResourceDefinition, V1CustomResourceDefinitionList> CRD =
      new RequestBuilder<>(V1CustomResourceDefinition.class, V1CustomResourceDefinitionList.class,
          "apiextensions.k8s.io", "v1", "customresourcedefinitions");
  public static final RequestBuilder<V1ValidatingWebhookConfiguration, V1ValidatingWebhookConfigurationList> VWC =
      new RequestBuilder<>(V1ValidatingWebhookConfiguration.class, V1ValidatingWebhookConfigurationList.class,
          "admissionregistration.k8s.io", "v1", "validatingwebhookconfigurations");

  public static final RequestBuilder<V1Job, V1JobList> JOB =
      new RequestBuilder<>(V1Job.class, V1JobList.class, "batch", "v1", "jobs");
  public static final RequestBuilder<V1PodDisruptionBudget, V1PodDisruptionBudgetList> PDB =
      new RequestBuilder<>(V1PodDisruptionBudget.class, V1PodDisruptionBudgetList.class,
          "policy", "v1", "poddisruptionbudgets");
  public static final RequestBuilder<V1TokenReview, KubernetesListObject> TR =
      new RequestBuilder<>(V1TokenReview.class, KubernetesListObject.class,
          "authentication.k8s.io", "v1", "tokenreviews");
  public static final RequestBuilder<V1SelfSubjectRulesReview, KubernetesListObject> SSRR =
      new RequestBuilder<>(V1SelfSubjectRulesReview.class, KubernetesListObject.class,
          "authorization.k8s.io", "v1", "selfsubjectrulesreviews");
  public static final RequestBuilder<V1SubjectAccessReview, KubernetesListObject> SAR =
      new RequestBuilder<>(V1SubjectAccessReview.class, KubernetesListObject.class,
          "authorization.k8s.io", "v1", "selfsubjectaccessreviews");

  protected final Class<A> apiTypeClass;
  protected final Class<L> apiListTypeClass;
  protected final String apiGroup;
  protected final String apiVersion;
  protected final String resourcePlural;

  RequestBuilder(
      Class<A> apiTypeClass,
      Class<L> apiListTypeClass,
      String apiGroup,
      String apiVersion,
      String resourcePlural) {
    this.apiGroup = apiGroup;
    this.apiVersion = apiVersion;
    this.resourcePlural = resourcePlural;
    this.apiTypeClass = apiTypeClass;
    this.apiListTypeClass = apiListTypeClass;
  }

  /**
   * Step to create resource.
   * @param object Resource
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> create(A object, ResponseStep<A> responseStep) {
    return create(object, new CreateOptions(), responseStep);
  }

  /**
   * Step to create resource.
   * @param object Resource
   * @param createOptions Create options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> create(
      A object, CreateOptions createOptions, ResponseStep<A> responseStep) {
    return new RequestStep.CreateRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        object, createOptions);
  }

  /**
   * Create resource.
   * @param object Resource
   * @return Created resource
   * @throws ApiException On failure
   */
  public A create(A object) throws ApiException {
    return create(object, new CreateOptions());
  }

  /**
   * Create resource.
   * @param object Resource
   * @param createOptions Create options
   * @return Created resource
   * @throws ApiException On failure
   */
  public A create(A object, CreateOptions createOptions) throws ApiException {
    DirectResponseStep<A> response = new DirectResponseStep<>();
    RequestStep<A, L, A> step = create(object, createOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * Step to delete resource.
   * @param name Name
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> delete(String name, ResponseStep<A> responseStep) {
    return delete(name, new DeleteOptions(), responseStep);
  }

  /**
   * Step to delete resource.
   * @param name Name
   * @param deleteOptions Delete options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> delete(
      String name, DeleteOptions deleteOptions, ResponseStep<A> responseStep) {
    return new RequestStep.ClusterDeleteRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        name, deleteOptions);
  }

  /**
   * Step to delete resource.
   * @param namespace Namespace
   * @param name Name
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> delete(
      String namespace, String name, ResponseStep<A> responseStep) {
    return delete(namespace, name, new DeleteOptions(), responseStep);
  }

  /**
   * Step to delete resource.
   * @param namespace Namespace
   * @param name Name
   * @param deleteOptions Delete options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> delete(
      String namespace, String name, DeleteOptions deleteOptions, ResponseStep<A> responseStep) {
    return new RequestStep.DeleteRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        namespace, name, deleteOptions);
  }

  /**
   * Delete resource.
   * @param name Name
   * @return Deleted resource
   * @throws ApiException On failure
   */
  public A delete(String name) throws ApiException {
    return delete(name, new DeleteOptions());
  }

  /**
   * Delete resource.
   * @param name Name
   * @param deleteOptions Delete options
   * @return Deleted resource
   * @throws ApiException On failure
   */
  public A delete(String name, DeleteOptions deleteOptions) throws ApiException {
    DirectResponseStep<A> response = new DirectResponseStep<>();
    RequestStep<A, L, A> step = delete(name, deleteOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * Delete resource.
   * @param namespace Namespace
   * @param name Name
   * @return Deleted resource
   * @throws ApiException On failure
   */
  public A delete(String namespace, String name) throws ApiException {
    return delete(namespace, name, new DeleteOptions());
  }

  /**
   * Delete resource.
   * @param namespace Namespace
   * @param name Name
   * @param deleteOptions Delete options
   * @return Deleted resource
   * @throws ApiException On failure
   */
  public A delete(String namespace, String name, DeleteOptions deleteOptions) throws ApiException {
    DirectResponseStep<A> response = new DirectResponseStep<>();
    RequestStep<A, L, A> step = delete(namespace, name, deleteOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * Get resource.
   * @param name Name
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> get(String name, ResponseStep<A> responseStep) {
    return get(name, new GetOptions(), responseStep);
  }

  /**
   * Get resource.
   * @param name Name
   * @param getOptions Get options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> get(
      String name, GetOptions getOptions, ResponseStep<A> responseStep) {
    return new RequestStep.ClusterGetRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        name, getOptions);
  }

  /**
   * Get resource.
   * @param namespace Namespace
   * @param name Name
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> get(
      String namespace, String name, ResponseStep<A> responseStep) {
    return get(namespace, name, new GetOptions(), responseStep);
  }

  /**
   * Get resource.
   * @param namespace Namespace
   * @param name Name
   * @param getOptions Get options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> get(
      String namespace, String name, GetOptions getOptions, ResponseStep<A> responseStep) {
    return new RequestStep.GetRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        namespace, name, getOptions);
  }

  /**
   * Get resource.
   * @param name Name
   * @return Resource
   * @throws ApiException On failure
   */
  public A get(String name) throws ApiException {
    return get(name, new GetOptions());
  }

  /**
   * Get resource.
   * @param name Name
   * @param getOptions Get options
   * @return Resource
   * @throws ApiException On failure
   */
  public A get(String name, GetOptions getOptions) throws ApiException {
    DirectResponseStep<A> response = new DirectResponseStep<>();
    RequestStep<A, L, A> step = get(name, getOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * Get resource.
   * @param namespace Namespace
   * @param name Name
   * @return Resource
   * @throws ApiException On failure
   */
  public A get(String namespace, String name) throws ApiException {
    return get(namespace, name, new GetOptions());
  }

  /**
   * Get resource.
   * @param namespace Namespace
   * @param name Name
   * @param getOptions Get options
   * @return Resource
   * @throws ApiException On failure
   */
  public A get(String namespace, String name, GetOptions getOptions) throws ApiException {
    DirectResponseStep<A> response = new DirectResponseStep<>();
    RequestStep<A, L, A> step = get(namespace, name, getOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * List resources.
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, L> list(ResponseStep<L> responseStep) {
    return list(new ListOptions(), responseStep);
  }

  /**
   * List resources.
   * @param listOptions List options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, L> list(
      ListOptions listOptions, ResponseStep<L> responseStep) {
    return new RequestStep.ClusterListRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        listOptions);
  }

  /**
   * List resources.
   * @param namespace Namespace
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, L> list(
      String namespace, ResponseStep<L> responseStep) {
    return list(namespace, new ListOptions(), responseStep);
  }

  /**
   * List resources.
   * @param namespace Namespace
   * @param listOptions List options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, L> list(
      String namespace, ListOptions listOptions, ResponseStep<L> responseStep) {
    return new RequestStep.ListRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        namespace, listOptions);
  }

  /**
   * List resources.
   * @return List of resources
   * @throws ApiException On failure
   */
  public L list() throws ApiException {
    return list(new ListOptions());
  }

  /**
   * List resources.
   * @param listOptions List options
   * @return List of resources
   * @throws ApiException On failure
   */
  public L list(ListOptions listOptions) throws ApiException {
    DirectResponseStep<L> response = new DirectResponseStep<>();
    RequestStep<A, L, L> step = list(listOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * List resources.
   * @param namespace Namespace
   * @return List of resources
   * @throws ApiException On failure
   */
  public L list(String namespace) throws ApiException {
    return list(namespace, new ListOptions());
  }

  /**
   * List resources.
   * @param namespace Namespace
   * @param listOptions List options
   * @return List of resources
   * @throws ApiException On failure
   */
  public L list(String namespace, ListOptions listOptions) throws ApiException {
    DirectResponseStep<L> response = new DirectResponseStep<>();
    RequestStep<A, L, L> step = list(namespace, listOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * Update resource.
   * @param object Resource
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> update(A object, ResponseStep<A> responseStep) {
    return update(object, new UpdateOptions(), responseStep);
  }

  /**
   * Update resource.
   * @param object Resource
   * @param updateOptions Update options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> update(
      A object, UpdateOptions updateOptions, ResponseStep<A> responseStep) {
    return new RequestStep.UpdateRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        object, updateOptions);
  }

  /**
   * Update resource.
   * @param object Resource
   * @return Resource
   * @throws ApiException On failure
   */
  public A update(A object) throws ApiException {
    return update(object, new UpdateOptions());
  }

  /**
   * Update resource.
   * @param object Resource
   * @param updateOptions Update options
   * @return Resource
   * @throws ApiException On failure
   */
  public A update(A object, UpdateOptions updateOptions) throws ApiException {
    DirectResponseStep<A> response = new DirectResponseStep<>();
    RequestStep<A, L, A> step = update(object, updateOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * Patch resource.
   * @param name Name
   * @param patchType Patch type
   * @param patch Patch
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> patch(
      String name, String patchType, V1Patch patch, ResponseStep<A> responseStep) {
    return patch(name, patchType, patch, new PatchOptions(), responseStep);
  }

  /**
   * Patch resource.
   * @param name Name
   * @param patchType Patch type
   * @param patch Patch
   * @param patchOptions Patch options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> patch(
      String name, String patchType, V1Patch patch, PatchOptions patchOptions, ResponseStep<A> responseStep) {
    return new RequestStep.ClusterPatchRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        name, patchType, patch, patchOptions);
  }

  /**
   * Patch resource.
   * @param namespace Namespace
   * @param name Name
   * @param patchType Patch type
   * @param patch Patch
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> patch(
      String namespace, String name, String patchType, V1Patch patch, ResponseStep<A> responseStep) {
    return patch(namespace, name, patchType, patch, new PatchOptions(), responseStep);
  }

  /**
   * Patch resource.
   * @param namespace Namespace
   * @param name Name
   * @param patchType Patch type
   * @param patch Patch
   * @param patchOptions Patch options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> patch(
      String namespace, String name, String patchType, V1Patch patch,
      PatchOptions patchOptions, ResponseStep<A> responseStep) {
    return new RequestStep.PatchRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        namespace, name, patchType, patch, patchOptions);
  }

  /**
   * Patch resource.
   * @param name Name
   * @param patchType Patch type
   * @param patch Patch
   * @return Resource
   * @throws ApiException On failure
   */
  public A patch(String name, String patchType, V1Patch patch) throws ApiException {
    return patch(name, patchType, patch, new PatchOptions());
  }

  /**
   * Patch resource.
   * @param name Name
   * @param patchType Patch type
   * @param patch Patch
   * @param patchOptions Patch options
   * @return Resource
   * @throws ApiException On failure
   */
  public A patch(String name, String patchType, V1Patch patch, PatchOptions patchOptions) throws ApiException {
    DirectResponseStep<A> response = new DirectResponseStep<>();
    RequestStep<A, L, A> step = patch(name, patchType, patch, patchOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * Patch resource.
   * @param namespace Namespace
   * @param name Name
   * @param patchType Patch type
   * @param patch Patch
   * @return Resource
   * @throws ApiException On failure
   */
  public A patch(String namespace, String name, String patchType, V1Patch patch) throws ApiException {
    return patch(namespace, name, patchType, patch, new PatchOptions());
  }

  /**
   * Patch resource.
   * @param namespace Namespace
   * @param name Name
   * @param patchType Patch type
   * @param patch Patch
   * @param patchOptions Patch options
   * @return Resource
   * @throws ApiException On failure
   */
  public A patch(String namespace, String name, String patchType, V1Patch patch,
                 PatchOptions patchOptions) throws ApiException {
    DirectResponseStep<A> response = new DirectResponseStep<>();
    RequestStep<A, L, A> step = patch(namespace, name, patchType, patch, patchOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  /**
   * Update status.
   * @param object Resource object
   * @param status Function to get status from resource
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> updateStatus(
      A object, Function<A, Object> status, ResponseStep<A> responseStep) {
    return updateStatus(object, status, new UpdateOptions(), responseStep);
  }

  /**
   * Update status.
   * @param object Resource object
   * @param status Function to get status from resource
   * @param updateOptions Update options
   * @param responseStep Response step
   * @return Request step
   */
  public RequestStep<A, L, A> updateStatus(
      A object, Function<A, Object> status,
      UpdateOptions updateOptions, ResponseStep<A> responseStep) {
    return new RequestStep.UpdateStatusRequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        object, status, updateOptions);
  }

  /**
   * Update status.
   * @param object Data object
   * @param status Function to get status from data object
   * @return Data object
   * @throws ApiException On failure
   */
  public A updateStatus(A object, Function<A, Object> status) throws ApiException {
    return updateStatus(object, status, new UpdateOptions());
  }

  /**
   * Update status.
   * @param object Data object
   * @param status Function to get status from data object
   * @param updateOptions Update options
   * @return Data object
   * @throws ApiException On failure
   */
  public A updateStatus(A object, Function<A, Object> status,
                        UpdateOptions updateOptions) throws ApiException {
    DirectResponseStep<A> response = new DirectResponseStep<>();
    RequestStep<A, L, A> step = updateStatus(object, status, updateOptions, response);
    step.apply(new Packet());
    return response.get();
  }

  private static class DirectResponseStep<R extends KubernetesType> extends ResponseStep<R> {
    private KubernetesApiResponse<R> callResponse;

    public Void onFailure(Packet packet, KubernetesApiResponse<R> callResponse) {
      this.callResponse = callResponse;
      return doEnd(packet);
    }

    public Void onSuccess(Packet packet, KubernetesApiResponse<R> callResponse) {
      this.callResponse = callResponse;
      return doEnd(packet);
    }

    public R get() throws ApiException {
      if (callResponse != null) {
        return callResponse.throwsApiException().getObject();
      }
      return null;
    }
  }

  public static class StringObject implements KubernetesObject {
    private final String value;

    public StringObject(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    @Override
    public V1ObjectMeta getMetadata() {
      return null;
    }

    @Override
    public String getApiVersion() {
      return null;
    }

    @Override
    public String getKind() {
      return null;
    }
  }

  public static class V1StatusObject implements KubernetesObject {
    private final V1Status value;

    public V1StatusObject(V1Status value) {
      this.value = value;
    }

    @Override
    public V1ObjectMeta getMetadata() {
      return null;
    }

    @Override
    public String getApiVersion() {
      return value.getApiVersion();
    }

    @Override
    public String getKind() {
      return value.getKind();
    }
  }

  public static class PodRequestBuilder extends RequestBuilder<V1Pod, V1PodList> {

    public PodRequestBuilder() {
      super(V1Pod.class, V1PodList.class, "", "v1", "pods");
    }

    /**
     * Step to return pod logs.
     * @param namespace Namespace
     * @param name Name
     * @param container Container name
     * @param responseStep Response step
     * @return Request step
     */
    public RequestStep<V1Pod, V1PodList, StringObject> logs(
        String namespace, String name, String container, ResponseStep<StringObject> responseStep) {
      return new RequestStep.LogsRequestStep(
          responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
          namespace, name, container);
    }

    /**
     * Step to delete collection of pods.
     * @param namespace Namespace
     * @param listOptions List options
     * @param deleteOptions Delete options
     * @param responseStep Response step
     * @return Request step
     */
    public RequestStep<V1Pod, V1PodList, V1StatusObject> deleteCollection(
        String namespace, ListOptions listOptions, DeleteOptions deleteOptions,
        ResponseStep<V1StatusObject> responseStep) {
      return new RequestStep.DeleteCollectionRequestStep(
          responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
          namespace, listOptions, deleteOptions);
    }
  }
}
