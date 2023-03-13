// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.common.KubernetesType;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import io.kubernetes.client.util.generic.options.CreateOptions;
import io.kubernetes.client.util.generic.options.DeleteOptions;
import io.kubernetes.client.util.generic.options.GetOptions;
import io.kubernetes.client.util.generic.options.ListOptions;
import io.kubernetes.client.util.generic.options.PatchOptions;
import io.kubernetes.client.util.generic.options.UpdateOptions;
import oracle.kubernetes.operator.work.AsyncFiber;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

/**
 * A Step driven by a call to the Kubernetes API.
 */
public abstract class RequestStep<
    ApiType extends KubernetesObject, ApiListType extends KubernetesListObject, ResponseType extends KubernetesType>
    extends Step {
  public static final String RESPONSE_COMPONENT_NAME = "response";
  public static final String CONTINUE = "continue";
  public static final int FIBER_TIMEOUT = 0;

  private final Class<ApiType> apiTypeClass;
  private final Class<ApiListType> apiListTypeClass;
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
          ResponseStep<ResponseType> next,
          Class<ApiType> apiTypeClass,
          Class<ApiListType> apiListTypeClass,
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

  abstract KubernetesApiResponse<ResponseType> execute(
      GenericKubernetesApi<ApiType, ApiListType> client, Packet packet);

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
    GenericKubernetesApi<ApiType, ApiListType> client = new GenericKubernetesApi<>(
        apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural, Client.getInstance());
    KubernetesApiResponse<ResponseType> result = execute(client, packet);

    // update packet
    packet.put(RESPONSE_COMPONENT_NAME, result);

    return doNext(packet);
  }

  // Schedule the timeout check to happen on the fiber at some number of seconds in the future.
  private void scheduleTimeoutCheck(AsyncFiber fiber, int timeoutSeconds, Runnable timeoutCheck) {
    fiber.scheduleOnce(timeoutSeconds, TimeUnit.SECONDS, timeoutCheck);
  }

  public static class ClusterGetRequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiType> {
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
        ResponseStep<ApiType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String name,
        GetOptions getOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.name = name;
      this.getOptions = getOptions;
    }

    public KubernetesApiResponse<ApiType> execute(
        GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      return client.get(name, getOptions);
    }
  }

  public static class GetRequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiType> {
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
        ResponseStep<ApiType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
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

    public KubernetesApiResponse<ApiType> execute(GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      return client.get(namespace, name, getOptions);
    }
  }

  public static class UpdateRequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiType> {
    private final ApiType object;
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
        ResponseStep<ApiType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        ApiType object,
        UpdateOptions updateOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.object = object;
      this.updateOptions = updateOptions;
    }

    public KubernetesApiResponse<ApiType> execute(GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      return client.update(object, updateOptions);
    }
  }

  public static class ClusterPatchRequestStep<ApiType extends KubernetesObject,
      ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiType> {
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
        ResponseStep<ApiType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
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

    public KubernetesApiResponse<ApiType> execute(GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      return client.patch(name, patchType, patch, patchOptions);
    }
  }

  public static class PatchRequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiType> {
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
        ResponseStep<ApiType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
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

    public KubernetesApiResponse<ApiType> execute(GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      return client.patch(namespace, name, patchType, patch, patchOptions);
    }
  }

  public static class ClusterDeleteRequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiType> {
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
        ResponseStep<ApiType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String name,
        DeleteOptions deleteOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.name = name;
      this.deleteOptions = deleteOptions;
    }

    public KubernetesApiResponse<ApiType> execute(GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      return client.delete(name, deleteOptions);
    }
  }

  public static class DeleteRequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiType> {
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
        ResponseStep<ApiType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
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

    public KubernetesApiResponse<ApiType> execute(GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      return client.delete(namespace, name, deleteOptions);
    }
  }

  public static class ClusterListRequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiListType> {
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
        ResponseStep<ApiListType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        ListOptions listOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.listOptions = listOptions;
    }

    public KubernetesApiResponse<ApiListType> execute(
        GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      return client.list(listOptions);
    }
  }

  public static class ListRequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiListType> {
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
        ResponseStep<ApiListType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        String namespace,
        ListOptions listOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.namespace = namespace;
      this.listOptions = listOptions;
    }

    public KubernetesApiResponse<ApiListType> execute(
        GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      String cont = (String) packet.remove(CONTINUE);
      if (cont != null) {
        listOptions.setContinue(cont);
      }
      return client.list(namespace, listOptions);
    }
  }

  public static class CreateRequestStep<ApiType extends KubernetesObject, ApiListType extends KubernetesListObject>
      extends RequestStep<ApiType, ApiListType, ApiType> {
    private final ApiType object;
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
        ResponseStep<ApiType> next,
        Class<ApiType> apiTypeClass,
        Class<ApiListType> apiListTypeClass,
        String apiGroup,
        String apiVersion,
        String resourcePlural,
        ApiType object,
        CreateOptions createOptions) {
      super(next, apiTypeClass, apiListTypeClass, apiGroup, apiVersion, resourcePlural);
      this.object = object;
      this.createOptions = createOptions;
    }

    public KubernetesApiResponse<ApiType> execute(GenericKubernetesApi<ApiType, ApiListType> client, Packet packet) {
      return client.create(object, createOptions);
    }
  }
}
