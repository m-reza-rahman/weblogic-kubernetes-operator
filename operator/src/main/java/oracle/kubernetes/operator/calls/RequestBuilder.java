// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.custom.V1Patch;
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
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodDisruptionBudget;
import io.kubernetes.client.openapi.models.V1PodDisruptionBudgetList;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.openapi.models.V1SubjectAccessReview;
import io.kubernetes.client.openapi.models.V1TokenReview;
import io.kubernetes.client.openapi.models.V1ValidatingWebhookConfiguration;
import io.kubernetes.client.openapi.models.V1ValidatingWebhookConfigurationList;
import io.kubernetes.client.util.generic.options.CreateOptions;
import io.kubernetes.client.util.generic.options.DeleteOptions;
import io.kubernetes.client.util.generic.options.GetOptions;
import io.kubernetes.client.util.generic.options.ListOptions;
import io.kubernetes.client.util.generic.options.PatchOptions;
import io.kubernetes.client.util.generic.options.UpdateOptions;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.ClusterList;
import oracle.kubernetes.weblogic.domain.model.ClusterResource;
import oracle.kubernetes.weblogic.domain.model.DomainList;
import oracle.kubernetes.weblogic.domain.model.DomainResource;

import java.util.function.Function;

public class RequestBuilder<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject> {
  public static final RequestBuilder<DomainResource, DomainList> DOMAIN =
      new RequestBuilder<>(DomainResource.class, DomainList.class, "weblogic.oracle", "v9", "domains");
  public static final RequestBuilder<ClusterResource, ClusterList> CLUSTER =
      new RequestBuilder<>(ClusterResource.class, ClusterList.class, "weblogic.oracle", "v1", "clusters");

  public static final RequestBuilder<V1Namespace, V1NamespaceList> NAMESPACE =
      new RequestBuilder<>(V1Namespace.class, V1NamespaceList.class, "", "v1", "namespaces");
  public static final RequestBuilder<V1Pod, V1PodList> POD =
      new RequestBuilder<>(V1Pod.class, V1PodList.class, "", "v1", "pods");
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
  public static final RequestBuilder<V1SubjectAccessReview, KubernetesListObject> SAR =
      new RequestBuilder<>(V1SubjectAccessReview.class, KubernetesListObject.class,
          "authorization.k8s.io", "v1", "subjectaccessreviews");

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

  public Step create(ApiType object, CreateOptions createOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        client -> client.create(object, createOptions));
  }

  public Step delete(String name, ResponseStep<ApiType> responseStep) {
    return delete(name, new DeleteOptions(), responseStep);
  }

  public Step delete(String name, DeleteOptions deleteOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        client -> client.delete(name, deleteOptions));
  }

  public Step delete(String namespace, String name, ResponseStep<ApiType> responseStep) {
    return delete(namespace, name, new DeleteOptions(), responseStep);
  }

  public Step delete(String namespace, String name, DeleteOptions deleteOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        client -> client.delete(namespace, name, deleteOptions));
  }

  public Step get(String name, ResponseStep<ApiType> responseStep) {
    return get(name, new GetOptions(), responseStep);
  }

  public Step get(String name, GetOptions getOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        client -> client.get(name, getOptions));
  }

  public Step get(String namespace, String name, ResponseStep<ApiType> responseStep) {
    return get(namespace, name, new GetOptions(), responseStep);
  }

  public Step get(String namespace, String name, GetOptions getOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        client -> client.get(namespace, name, getOptions));
  }

  public Step list(ResponseStep<ApiListType> responseStep) {
    return list(new ListOptions(), responseStep);
  }

  public Step list(ListOptions listOptions, ResponseStep<ApiListType> responseStep) {
    return null; // TODO: new ResponseStep...
  }

  public Step list(String namespace, ResponseStep<ApiListType> responseStep) {
    return list(namespace, new ListOptions(), responseStep);
  }

  public Step list(String namespace, ListOptions listOptions, ResponseStep<ApiListType> responseStep) {
    return null; // TODO: new ResponseStep...
  }

  public Step update(ApiType object, ResponseStep<ApiType> responseStep) {
    return update(object, new UpdateOptions(), responseStep);
  }

  public Step update(ApiType object, UpdateOptions updateOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        client -> client.update(object, updateOptions));
  }

  public Step patch(String name, String patchType, V1Patch patch, ResponseStep<ApiType> responseStep) {
    return patch(name, patchType, patch, new PatchOptions(), responseStep);
  }

  public Step patch(String name, String patchType, V1Patch patch, PatchOptions patchOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        client -> client.patch(name, patchType, patch, patchOptions));
  }

  public Step patch(String namespace, String name, String patchType, V1Patch patch, ResponseStep<ApiType> responseStep) {
    return patch(namespace, name, patchType, patch, new PatchOptions(), responseStep);
  }

  public Step patch(String namespace, String name, String patchType, V1Patch patch, PatchOptions patchOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        client -> client.patch(namespace, name, patchType, patch, patchOptions));
  }

  public Step updateStatus(ApiType object, Function<ApiType, Object> status, ResponseStep<ApiType> responseStep) {
    return updateStatus(object, status, new UpdateOptions(), responseStep);
  }

  public Step updateStatus(ApiType object, Function<ApiType, Object> status, UpdateOptions updateOptions, ResponseStep<ApiType> responseStep) {
    return new RequestStep<>(
        responseStep, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural,
        client -> client.updateStatus(object, status, updateOptions));
  }
}
