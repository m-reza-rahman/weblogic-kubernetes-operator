// Copyright (c) 2019, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.CoreV1EventList;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import io.kubernetes.client.openapi.models.V1PersistentVolume;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimList;
import io.kubernetes.client.openapi.models.V1PersistentVolumeList;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodDisruptionBudget;
import io.kubernetes.client.openapi.models.V1PodDisruptionBudgetList;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.openapi.models.V1ValidatingWebhookConfiguration;
import io.kubernetes.client.openapi.models.V1ValidatingWebhookConfigurationList;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.StreamResetException;
import oracle.kubernetes.operator.work.FiberTestSupport;
import oracle.kubernetes.weblogic.domain.model.ClusterList;
import oracle.kubernetes.weblogic.domain.model.ClusterResource;
import oracle.kubernetes.weblogic.domain.model.DomainList;
import oracle.kubernetes.weblogic.domain.model.DomainResource;

import javax.annotation.Nonnull;
import java.util.List;

@SuppressWarnings("WeakerAccess")
public class KubernetesTestSupport extends FiberTestSupport {
  public static final String CONFIG_MAP = "ConfigMap";
  public static final String CUSTOM_RESOURCE_DEFINITION = "CRD";
  public static final String NAMESPACE = "Namespace";
  public static final String CLUSTER = "Cluster";
  public static final String CLUSTER_STATUS = "ClusterStatus";
  public static final String DOMAIN = "Domain";
  public static final String DOMAIN_STATUS = "DomainStatus";
  public static final String EVENT = "Event";
  public static final String JOB = "Job";
  public static final String PV = "PersistentVolume";
  public static final String PVC = "PersistentVolumeClaim";
  public static final String POD = "Pod";
  public static final String PODDISRUPTIONBUDGET = "PodDisruptionBudget";
  public static final String PODLOG = "PodLog";
  public static final String SECRET = "Secret";
  public static final String SERVICE = "Service";
  public static final String SCALE = "Scale";
  public static final String SUBJECT_ACCESS_REVIEW = "SubjectAccessReview";
  public static final String SELF_SUBJECT_ACCESS_REVIEW = "SelfSubjectAccessReview";
  public static final String SELF_SUBJECT_RULES_REVIEW = "SelfSubjectRulesReview";
  public static final String TOKEN_REVIEW = "TokenReview";
  public static final String VALIDATING_WEBHOOK_CONFIGURATION = "ValidatingWebhookConfiguration";

  private long resourceVersion;

  private ClusterList createClusterList(List<ClusterResource> items) {
    return new ClusterList().withMetadata(createListMeta()).withItems(items);
  }

  private V1ConfigMapList createConfigMapList(List<V1ConfigMap> items) {
    return new V1ConfigMapList().metadata(createListMeta()).items(items);
  }

  private DomainList createDomainList(List<DomainResource> items) {
    return new DomainList().withMetadata(createListMeta()).withItems(items);
  }

  private CoreV1EventList createEventList(List<CoreV1Event> items) {
    return new CoreV1EventList().metadata(createListMeta()).items(items);
  }

  private V1PersistentVolumeList createPvList(List<V1PersistentVolume> items) {
    return new V1PersistentVolumeList().metadata(createListMeta()).items(items);
  }

  private V1PersistentVolumeClaimList createPvcList(List<V1PersistentVolumeClaim> items) {
    return new V1PersistentVolumeClaimList().metadata(createListMeta()).items(items);
  }

  private V1NamespaceList createNamespaceList(List<V1Namespace> items) {
    return new V1NamespaceList().metadata(createListMeta()).items(items);
  }

  private V1ValidatingWebhookConfigurationList createValidatingWebhookConfigurationList(
      List<V1ValidatingWebhookConfiguration> items) {
    return new V1ValidatingWebhookConfigurationList().metadata(createListMeta()).items(items);
  }

  private V1PodList createPodList(List<V1Pod> items) {
    return new V1PodList().metadata(createListMeta()).items(items);
  }

  private V1JobList createJobList(List<V1Job> items) {
    return new V1JobList().metadata(createListMeta()).items(items);
  }

  private V1SecretList createSecretList(List<V1Secret> items) {
    return new V1SecretList().metadata(createListMeta()).items(items);
  }

  private V1ServiceList createServiceList(List<V1Service> items) {
    return new V1ServiceList().metadata(createListMeta()).items(items);
  }

  private V1PodDisruptionBudgetList createPodDisruptionBudgetList(List<V1PodDisruptionBudget> items) {
    return new V1PodDisruptionBudgetList().metadata(createListMeta()).items(items);
  }

  private V1ListMeta createListMeta() {
    return new V1ListMeta().resourceVersion(Long.toString(++resourceVersion));
  }

  /**
   * define resources.
   * @param resources resources.
   * @param <T> type
   */
  @SafeVarargs
  public final <T> void defineResources(T... resources) {
    // TODO
  }

  /**
   * Specifies that a read operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   * @param httpStatus the status to associate with the failure
   */
  public void failOnRead(String resourceType, String name, String namespace, int httpStatus) {
    // TODO
  }

  /**
   * Specifies that a list operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param namespace the namespace containing the resource
   * @param httpStatus the status to associate with the failure
   */
  public void failOnList(String resourceType, String namespace, int httpStatus) {
    // TODO
  }

  /**
   * Specifies that a create operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param namespace the namespace containing the resource
   * @param httpStatus the status to associate with the failure
   */
  public void failOnCreate(String resourceType, String namespace, int httpStatus) {
    // TODO
  }

  /**
   * Specifies that a create operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param namespace the namespace containing the resource
   * @param apiException the Kubernetes exception to associate with the failure
   */
  public void failOnCreate(String resourceType, String namespace, ApiException apiException) {
    // TODO
  }

  /**
   * Specifies that a replace operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   * @param httpStatus the status to associate with the failure
   */
  public void failOnReplace(String resourceType, String name, String namespace, int httpStatus) {
    // TODO
  }

  /**
   * Specifies that a replace operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   * @param httpStatus the status to associate with the failure
   */
  public void failOnReplaceStatus(String resourceType, String name, String namespace, int httpStatus) {
    // TODO
  }

  /**
   * Specifies that a replace operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   */
  public void failOnReplaceWithStreamResetException(String resourceType, String name, String namespace) {
    ApiException ae = new ApiException("StreamResetException: stream was reset: NO_ERROR",
        new StreamResetException(ErrorCode.NO_ERROR), 0, null, null);
    // TODO
  }

  /**
   * Specifies that a delete operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   * @param httpStatus the status to associate with the failure
   */
  public void failOnDelete(String resourceType, String name, String namespace, int httpStatus) {
    // TODO
  }

  /**
   * Specifies that any operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   * @param httpStatus the status to associate with the failure
   */
  public void failOnResource(String resourceType, String name, String namespace, int httpStatus) {
    // TODO
  }

  /**
   * Specifies that any operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   * @param apiException the kubernetes failure to associate with the failure
   */
  public void failOnResource(@Nonnull String resourceType, String name, String namespace, ApiException apiException) {
    // TODO
  }
}
