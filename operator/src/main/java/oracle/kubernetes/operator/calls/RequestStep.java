// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.gson.JsonSyntaxException;
import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.common.KubernetesType;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Status;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import io.kubernetes.client.util.generic.options.CreateOptions;
import io.kubernetes.client.util.generic.options.DeleteOptions;
import io.kubernetes.client.util.generic.options.GetOptions;
import io.kubernetes.client.util.generic.options.ListOptions;
import io.kubernetes.client.util.generic.options.PatchOptions;
import io.kubernetes.client.util.generic.options.UpdateOptions;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

/**
 * A Step driven by a call to the Kubernetes API.
 */
public abstract class RequestStep<
    A extends KubernetesObject, L extends KubernetesListObject, R extends KubernetesType>
    extends Step {
  public static final String RESPONSE_COMPONENT_NAME = "response";
  public static final String CONTINUE = "continue";
  public static final int FIBER_TIMEOUT = 0;

  private final Class<A> apiTypeClass;
  private final Class<L> apiListTypeClass;
  private final String apiGroup;
  private final String apiVersion;
  private final String resourcePlural;

  /**
   * Construct request step.
   *
   * @param next Response step
   * @param apiTypeClass API type class
   * @param apiListTypeClass API list type class
   * @param apiGroup API group
   * @param apiVersion API version
   * @param resourcePlural Resource plural
   */
  protected RequestStep(
          ResponseStep<R> next,
          Class<A> apiTypeClass,
          Class<L> apiListTypeClass,
          String apiGroup,
          String apiVersion,
          String resourcePlural) {
    super(next);
    this.apiGroup = apiGroup;
    this.apiVersion = apiVersion;
    this.resourcePlural = resourcePlural;
    this.apiTypeClass = apiTypeClass;
    this.apiListTypeClass = apiListTypeClass;

    next.setPrevious(this);
  }

  abstract KubernetesApiResponse<R> execute(
      GenericKubernetesApi<A, L> client, Packet packet);

  /**
   * Access continue field, if any, from list metadata.
   * @param result Kubernetes list result
   * @return Continue value
   */
  public static String accessContinue(Object result) {
    return Optional.ofNullable(result)
        .filter(KubernetesListObject.class::isInstance)
        .map(KubernetesListObject.class::cast)
        .map(KubernetesListObject::getMetadata)
        .map(V1ListMeta::getContinue)
        .filter(Predicate.not(String::isEmpty))
        .orElse(null);
  }

  @Override
  public Void apply(Packet packet) {
    GenericKubernetesApi<A, L> client = new GenericKubernetesApi<>(
        apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural, Client.getInstance());
    KubernetesApiResponse<R> result = execute(client, packet);

    // update packet
    packet.put(RESPONSE_COMPONENT_NAME, result);

    return doNext(packet);
  }

  public static class ClusterGetRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, A> {
    private final String name;
    private final GetOptions getOptions;

    /**
     * Construct get request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param name Name
     * @param getOptions Get options
     */
    public ClusterGetRequestStep(
        ResponseStep<A> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String name,
        GetOptions getOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.name = name;
      this.getOptions = getOptions;
    }

    KubernetesApiResponse<A> execute(
        GenericKubernetesApi<A, L> client, Packet packet) {
      return client.get(name, getOptions);
    }
  }

  public static class GetRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, A> {
    private final String namespace;
    private final String name;
    private final GetOptions getOptions;

    /**
     * Construct get request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param namespace Namespace
     * @param name Name
     * @param getOptions Get options
     */
    public GetRequestStep(
        ResponseStep<A> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String namespace,
        String name,
        GetOptions getOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.namespace = namespace;
      this.name = name;
      this.getOptions = getOptions;
    }

    KubernetesApiResponse<A> execute(GenericKubernetesApi<A, L> client, Packet packet) {
      return client.get(namespace, name, getOptions);
    }
  }

  public static class UpdateRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, A> {
    private final A object;
    private final UpdateOptions updateOptions;

    /**
     * Construct update request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param object Object
     * @param updateOptions Update options
     */
    public UpdateRequestStep(
        ResponseStep<A> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        A object,
        UpdateOptions updateOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.object = object;
      this.updateOptions = updateOptions;
    }

    KubernetesApiResponse<A> execute(GenericKubernetesApi<A, L> client, Packet packet) {
      return client.update(object, updateOptions);
    }
  }

  public static class ClusterPatchRequestStep<A extends KubernetesObject,
      L extends KubernetesListObject>
      extends RequestStep<A, L, A> {
    private final String name;
    private final String patchType;
    private final V1Patch patch;
    private final PatchOptions patchOptions;

    /**
     * Construct get request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param name Name
     * @param patchType Patch type
     * @param patch Patch
     * @param patchOptions Patch options
     */
    public ClusterPatchRequestStep(
        ResponseStep<A> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String name,
        String patchType,
        V1Patch patch,
        PatchOptions patchOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.name = name;
      this.patchType = patchType;
      this.patch = patch;
      this.patchOptions = patchOptions;
    }

    KubernetesApiResponse<A> execute(GenericKubernetesApi<A, L> client, Packet packet) {
      return client.patch(name, patchType, patch, patchOptions);
    }
  }

  public static class PatchRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, A> {
    private final String namespace;
    private final String name;
    private final String patchType;
    private final V1Patch patch;
    private final PatchOptions patchOptions;

    /**
     * Construct get request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param namespace Namespace
     * @param name Name
     * @param patchType Patch type
     * @param patch Patch
     * @param patchOptions Patch options
     */
    public PatchRequestStep(
        ResponseStep<A> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String namespace,
        String name,
        String patchType,
        V1Patch patch,
        PatchOptions patchOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.namespace = namespace;
      this.name = name;
      this.patchType = patchType;
      this.patch = patch;
      this.patchOptions = patchOptions;
    }

    KubernetesApiResponse<A> execute(GenericKubernetesApi<A, L> client, Packet packet) {
      return client.patch(namespace, name, patchType, patch, patchOptions);
    }
  }

  public static class ClusterDeleteRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, A> {
    private final String name;
    private final DeleteOptions deleteOptions;

    /**
     * Construct delete request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param name Name
     * @param deleteOptions Delete options
     */
    public ClusterDeleteRequestStep(
        ResponseStep<A> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String name,
        DeleteOptions deleteOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.name = name;
      this.deleteOptions = deleteOptions;
    }

    KubernetesApiResponse<A> execute(GenericKubernetesApi<A, L> client, Packet packet) {
      return client.delete(name, deleteOptions);
    }
  }

  public static class DeleteRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, A> {
    private final String namespace;
    private final String name;
    private final DeleteOptions deleteOptions;

    /**
     * Construct delete request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param namespace Namespace
     * @param name Name
     * @param deleteOptions Delete options
     */
    public DeleteRequestStep(
        ResponseStep<A> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String namespace,
        String name,
        DeleteOptions deleteOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.namespace = namespace;
      this.name = name;
      this.deleteOptions = deleteOptions;
    }

    KubernetesApiResponse<A> execute(GenericKubernetesApi<A, L> client, Packet packet) {
      return client.delete(namespace, name, deleteOptions);
    }
  }

  public static class ClusterListRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, L> {
    private final ListOptions listOptions;

    /**
     * Construct list request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param listOptions List options
     */
    public ClusterListRequestStep(
        ResponseStep<L> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        ListOptions listOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.listOptions = listOptions;
    }

    KubernetesApiResponse<L> execute(
        GenericKubernetesApi<A, L> client, Packet packet) {
      return client.list(listOptions);
    }
  }

  public static class ListRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, L> {
    private final String namespace;
    private final ListOptions listOptions;

    /**
     * Construct list request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param namespace Namespace
     * @param listOptions List options
     */
    public ListRequestStep(
        ResponseStep<L> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String namespace,
        ListOptions listOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.namespace = namespace;
      this.listOptions = listOptions;
    }

    KubernetesApiResponse<L> execute(
        GenericKubernetesApi<A, L> client, Packet packet) {
      String cont = (String) packet.remove(CONTINUE);
      if (cont != null) {
        listOptions.setContinue(cont);
      }
      return client.list(namespace, listOptions);
    }
  }

  public static class CreateRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, A> {
    private final A object;
    private final CreateOptions createOptions;

    /**
     * Construct create request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param object Object
     * @param createOptions Create options
     */
    public CreateRequestStep(
        ResponseStep<A> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        A object,
        CreateOptions createOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.object = object;
      this.createOptions = createOptions;
    }

    KubernetesApiResponse<A> execute(GenericKubernetesApi<A, L> client, Packet packet) {
      return client.create(object, createOptions);
    }
  }

  public static class UpdateStatusRequestStep<A extends KubernetesObject, L extends KubernetesListObject>
      extends RequestStep<A, L, A> {
    private final A object;
    private final Function<A, Object> status;
    private final UpdateOptions updateOptions;

    /**
     * Construct update request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param object Object
     * @param status Function to generate status from object
     * @param updateOptions Update options
     */
    public UpdateStatusRequestStep(
        ResponseStep<A> next,
        Class<A> apiTypeClass,
        Class<L> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        A object,
        Function<A, Object> status,
        UpdateOptions updateOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.object = object;
      this.status = status;
      this.updateOptions = updateOptions;
    }

    KubernetesApiResponse<A> execute(GenericKubernetesApi<A, L> client, Packet packet) {
      return client.updateStatus(object, status, updateOptions);
    }
  }

  private static void checkForIOException(ApiException e) {
    if (e.getCause() instanceof IOException) {
      throw new IllegalStateException(e.getCause()); // make this a checked exception?
    }
  }

  private static <D extends KubernetesType> KubernetesApiResponse<D> responseFromApiException(
      ApiClient apiClient, ApiException e) {
    checkForIOException(e);
    final V1Status status;
    try {
      status = apiClient.getJSON().deserialize(e.getResponseBody(), V1Status.class);
    } catch (JsonSyntaxException jsonEx) {
      return new KubernetesApiResponse<>(
          new V1Status().code(e.getCode()).message(e.getResponseBody()), e.getCode());
    }
    if (null == status) {
      throw new RuntimeException(e);
    }
    return new KubernetesApiResponse<>(status, e.getCode());
  }

  public static class LogsRequestStep extends RequestStep<V1Pod, V1PodList, RequestBuilder.StringObject> {
    private final String namespace;
    private final String name;
    private final String container;

    /**
     * Construct logs request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param namespace Namespace
     * @param name Name
     * @param container Container
     */
    public LogsRequestStep(
        ResponseStep<RequestBuilder.StringObject> next,
        Class<V1Pod> apiTypeClass,
        Class<V1PodList> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String namespace,
        String name,
        String container) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.namespace = namespace;
      this.name = name;
      this.container = container;
    }

    KubernetesApiResponse<RequestBuilder.StringObject> execute(
        GenericKubernetesApi<V1Pod, V1PodList> client, Packet packet) {
      CoreV1Api c = new CoreV1Api(Client.getInstance());
      try {
        return new KubernetesApiResponse<>(new RequestBuilder.StringObject(
            c.readNamespacedPodLog(name, namespace, container,
            null, null, null, null, null, null, null, null)));
      } catch (ApiException e) {
        return responseFromApiException(c.getApiClient(), e);
      }
    }
  }

  public static class DeleteCollectionRequestStep extends RequestStep<V1Pod, V1PodList, RequestBuilder.V1StatusObject> {
    private final String namespace;
    private final ListOptions listOptions;
    private final DeleteOptions deleteOptions;

    /**
     * Construct logs request step.
     *
     * @param next Response step
     * @param apiTypeClass API type class
     * @param apiListTypeClass API list type class
     * @param apiGroup API group
     * @param apiVersion API version
     * @param resourcePlural Resource plural
     * @param namespace Namespace
     * @param listOptions List options
     * @param deleteOptions Delete options
     */
    public DeleteCollectionRequestStep(
        ResponseStep<RequestBuilder.V1StatusObject> next,
        Class<V1Pod> apiTypeClass,
        Class<V1PodList> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String namespace,
        ListOptions listOptions,
        DeleteOptions deleteOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.namespace = namespace;
      this.listOptions = listOptions;
      this.deleteOptions = deleteOptions;
    }

    KubernetesApiResponse<RequestBuilder.V1StatusObject> execute(
        GenericKubernetesApi<V1Pod, V1PodList> client, Packet packet) {
      CoreV1Api c = new CoreV1Api(Client.getInstance());
      try {
        return new KubernetesApiResponse<>(new RequestBuilder.V1StatusObject(
            c.deleteCollectionNamespacedPod(namespace, null, null, null, listOptions.getFieldSelector(), null,
                listOptions.getLabelSelector(), null, null, null, null, null, null, deleteOptions)));
      } catch (ApiException e) {
        return responseFromApiException(c.getApiClient(), e);
      }
    }
  }
}
