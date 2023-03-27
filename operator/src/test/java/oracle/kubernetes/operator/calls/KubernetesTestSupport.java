// Copyright (c) 2019, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.meterware.simplestub.Memento;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.CoreV1EventList;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
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
import io.kubernetes.client.openapi.models.V1Status;
import io.kubernetes.client.openapi.models.V1ValidatingWebhookConfiguration;
import io.kubernetes.client.openapi.models.V1ValidatingWebhookConfigurationList;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.StreamResetException;
import oracle.kubernetes.operator.work.FiberTestSupport;
import oracle.kubernetes.weblogic.domain.model.ClusterList;
import oracle.kubernetes.weblogic.domain.model.ClusterResource;
import oracle.kubernetes.weblogic.domain.model.DomainList;
import oracle.kubernetes.weblogic.domain.model.DomainResource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

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

  public Memento install(WireMockRule rule) {
    return new KubernetesTestSupportMemento(rule);
  }

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

  @SuppressWarnings("unchecked")
  public <T extends KubernetesObject> List<T> getResources(String resourceType) {
    // TODO
    return null;
  }

  /**
   * get resource with name.
   * @param resourceType resource type
   * @param name name
   * @param <T> type
   * @return resource
   */
  @SuppressWarnings("unchecked")
  public <T> T getResourceWithName(String resourceType, String name) {
    return (T)
        getResources(resourceType).stream()
            .filter(o -> name.equals(getResourceName(o)))
            .findFirst()
            .orElse(null);
  }

  private String getResourceName(KubernetesObject resource) {
    return Optional.ofNullable(resource).map(KubernetesObject::getMetadata)
        .map(V1ObjectMeta::getName).orElse(null);
  }

  private String getNamespace(KubernetesObject resource) {
    return Optional.ofNullable(resource).map(KubernetesObject::getMetadata)
        .map(V1ObjectMeta::getNamespace).orElse("default");
  }

  /**
   * define resources.
   * @param resources resources.
   * @param <T> type
   */
  @SafeVarargs
  public final <T extends KubernetesObject> void defineResources(T... resources) {
    JSON json = Client.getInstance().getJSON();
    for (KubernetesObject ko : resources) {
      RequestBuilder<?, ?> builder = RequestBuilder.lookupByType(ko.getClass());
      String path =
          "/apis/" + builder.getApiGroup() + "/" + builder.getApiVersion()
              + "/namespaces/" + getNamespace(ko) + "/"
              + builder.getResourcePlural() + "/" + getResourceName(ko);
      stubFor(get(urlEqualTo(path))
          .willReturn(aResponse()
              .withHeader("Content-Type", "application/json")
              .withBody(json.serialize(ko))));
    }
  }

  /**
   * delete resources.
   * @param resources resources.
   * @param <T> type
   */
  @SafeVarargs
  public final <T> void deleteResources(T... resources) {
    for (T resource : resources) {
      // TODO
    }
  }

  public void definePodLog(String name, String namespace, Object contents) {
    // TODO
  }

  /**
   * Deletes the specified namespace and all resources in that namespace.
   * @param namespaceName the name of the namespace to delete
   */
  public void deleteNamespace(String namespaceName) {
    // TODO
  }

  public void doOnCreate(String resourceType, Consumer<?> consumer) {
    // FIXME
  }

  public void doOnUpdate(String resourceType, Consumer<?> consumer) {
    // FIXME
  }

  public void doOnDelete(String resourceType, Consumer<Integer> consumer) {
    // FIXME
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
   * @param requestBuilder the request builder
   * @param namespace the namespace containing the resource
   * @param httpStatus the status to associate with the failure
   * @param statusMessage the status message
   */
  public void failOnCreate(RequestBuilder<?, ?> requestBuilder, String namespace,
                           int httpStatus, String statusMessage) {
    String url = "/api/" + requestBuilder.getApiVersion() + "/namespaces/"
        + namespace + "/" + requestBuilder.getResourcePlural();
    stubFor(post(urlEqualTo(url)).willReturn(
        aResponse()
            .withStatusMessage(statusMessage)
            .withStatus(httpStatus)));
  }

  /**
   * Specifies that a create operation should fail if it matches the specified conditions. Applies to
   * namespaced resources and replaces any existing failure checks.
   *
   * @param resourceType the type of resource
   * @param namespace the namespace containing the resource
   * @param status the Kubernetes status to associate with the failure
   */
  public void failOnCreate(String resourceType, String namespace, V1Status status) {
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
   * @param status the status to associate with the failure
   */
  public void failOnResource(@Nonnull String resourceType, String name, String namespace, V1Status status) {
    // TODO
  }

  /**
   * Specifies that status replacement operation should respond with a null result if it matches the specified
   * conditions. Applies to domain resources.
   *
   * @param resourceType the type of resource
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   */
  public void returnEmptyResult(String resourceType, String name, String namespace) {
    // TODO
  }

  /**
   * Specifies that a read operation should respond with a null result if it matches the specified conditions.
   * Applies to domain resources.
   *
   * @param resourceType the type of resource
   * @param name the name of the resource
   * @param namespace the namespace containing the resource
   */
  public void returnEmptyResultOnRead(String resourceType, String name, String namespace) {
    // TODO
  }

  private class KubernetesTestSupportMemento implements Memento {
    Memento clientFactory;

    public KubernetesTestSupportMemento(WireMockRule rule) {
      try {
        clientFactory = ClientFactoryStub.install(rule);
      } catch (NoSuchFieldException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void revert() {
      if (clientFactory != null) {
        clientFactory.revert();
      }
    }

    @Override
    public <T> T getOriginalValue() {
      throw new UnsupportedOperationException();
    }
  }
}
