// Copyright (c) 2023, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolume;
import io.kubernetes.client.openapi.models.V1PersistentVolumeSpec;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import oracle.kubernetes.operator.KubernetesConstants;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.calls.RequestBuilder;
import oracle.kubernetes.operator.calls.ResponseStep;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.steps.DefaultResponseStep;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.DomainResource;
import oracle.kubernetes.weblogic.domain.model.PersistentVolume;
import oracle.kubernetes.weblogic.domain.model.PersistentVolumeSpec;

import javax.annotation.Nonnull;

import static oracle.kubernetes.common.logging.MessageKeys.PV_CREATED;
import static oracle.kubernetes.common.logging.MessageKeys.PV_EXISTS;
import static oracle.kubernetes.operator.DomainStatusUpdater.createKubernetesFailureSteps;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_NOT_FOUND;
import static oracle.kubernetes.operator.LabelConstants.CREATEDBYOPERATOR_LABEL;
import static oracle.kubernetes.operator.LabelConstants.DOMAINUID_LABEL;

/**
 * Operations for dealing with persistent volumes.
 */
public class PersistentVolumeHelper {

  private PersistentVolumeHelper() {
    // no-op
  }

  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  /**
   * Factory for {@link Step} that verifies and creates persistent volume if needed.
   *
   * @param next the next step
   * @return Step for creating persistent volume
   */
  public static Step createPersistentVolumeStep(Step next) {
    return new CreatePersistentVolumeStep(next);
  }

  static class CreatePersistentVolumeStep extends Step {

    CreatePersistentVolumeStep(Step next) {
      super(next);
    }

    @Override
    public @Nonnull Result apply(Packet packet) {
      DomainPresenceInfo info = (DomainPresenceInfo) packet.get(ProcessingConstants.DOMAIN_PRESENCE_INFO);
      if (info.getDomain().getInitPvDomainPersistentVolume() != null) {
        return doNext(createContext(packet).readAndCreatePersistentVolumeStep(getNext()), packet);
      }
      return doNext(packet);
    }

    protected PersistentVolumeHelper.PersistentVolumeContext createContext(Packet packet) {
      return new PersistentVolumeHelper.PersistentVolumeContext(this, packet);
    }
  }

  static class PersistentVolumeContext extends StepContextBase {
    private final Step conflictStep;

    PersistentVolumeContext(Step conflictStep, Packet packet) {
      super((DomainPresenceInfo) packet.get(ProcessingConstants.DOMAIN_PRESENCE_INFO));
      this.conflictStep = conflictStep;
    }

    Step getConflictStep() {
      return new PersistentVolumeHelper.PersistentVolumeContext.ConflictStep();
    }

    Step readAndCreatePersistentVolumeStep(Step next) {
      return RequestBuilder.PV.get(getPersistentVolumeName(), new ReadResponseStep(next));
    }

    private String getPersistentVolumeName() {
      return Optional.ofNullable(getInitPvDomainPersistentVolume())
          .map(PersistentVolume::getMetadata).map(V1ObjectMeta::getName).orElse(null);
    }

    private PersistentVolume getInitPvDomainPersistentVolume() {
      return getDomain().getInitPvDomainPersistentVolume();
    }

    DomainResource getDomain() {
      return info.getDomain();
    }

    String getDomainUid() {
      return getDomain().getDomainUid();
    }

    protected String getPVCreatedMessageKey() {
      return PV_CREATED;
    }

    private class CreateResponseStep extends ResponseStep<V1PersistentVolume> {
      private final String messageKey;

      CreateResponseStep(String messageKey, Step next) {
        super(next);
        this.messageKey = messageKey;
      }

      @Override
      public Result onFailure(Packet packet, KubernetesApiResponse<V1PersistentVolume> callResponse) {
        if (isUnrecoverable(callResponse)) {
          return updateDomainStatus(packet, callResponse);
        } else {
          return onFailure(getConflictStep(), packet, callResponse);
        }
      }

      private Result updateDomainStatus(Packet packet, KubernetesApiResponse<V1PersistentVolume> callResponse) {
        return doNext(createKubernetesFailureSteps(callResponse, createFailureMessage(callResponse)), packet);
      }

      @Override
      public Result onSuccess(Packet packet, KubernetesApiResponse<V1PersistentVolume> callResponse) {
        logPersistentVolumeCreated(messageKey);
        return doNext(packet);
      }
    }

    private class ReadResponseStep extends DefaultResponseStep<V1PersistentVolume> {
      ReadResponseStep(Step next) {
        super(next);
      }

      @Override
      public Result onFailure(Packet packet, KubernetesApiResponse<V1PersistentVolume> callResponse) {
        return callResponse.getHttpStatusCode() == HTTP_NOT_FOUND
                ? onSuccess(packet, callResponse)
                : super.onFailure(packet, callResponse);
      }

      @Override
      public Result onSuccess(Packet packet, KubernetesApiResponse<V1PersistentVolume> callResponse) {
        DomainPresenceInfo info = (DomainPresenceInfo) packet.get(ProcessingConstants.DOMAIN_PRESENCE_INFO);
        V1PersistentVolume persistentVolume = callResponse.getObject();
        if (persistentVolume == null) {
          return doNext(createNewPersistentVolume(getNext()), packet);
        } else {
          logPersistentVolumeExists(info.getDomain().getDomainUid(),
              info.getDomain().getInitPvDomainPersistentVolume());
        }
        return doNext(packet);
      }

      protected void logPersistentVolumeExists(String domainUid, PersistentVolume pv) {
        LOGGER.fine(PV_EXISTS, pv.getMetadata().getName(), domainUid);
      }

      private Step createNewPersistentVolume(Step next) {
        return createPersistentVolume(getPVCreatedMessageKey(), next);
      }

      private Step createPersistentVolume(String messageKey, Step next) {
        return RequestBuilder.PV.create(createModel(), new PersistentVolumeHelper.PersistentVolumeContext
            .CreateResponseStep(messageKey, next));
      }
    }

    private class ConflictStep extends Step {
      @Override
      public @Nonnull Result apply(Packet packet) {
        return doNext(RequestBuilder.PV.get(getPersistentVolumeName(info),
            new ReadResponseStep(conflictStep)), packet);
      }

      private String getPersistentVolumeName(DomainPresenceInfo info) {
        return getInitPvDomainPersistentVolume(info).getMetadata().getName();
      }

      private PersistentVolume getInitPvDomainPersistentVolume(DomainPresenceInfo info) {
        return info.getDomain().getInitPvDomainPersistentVolume();
      }
    }

    public V1PersistentVolume createModel() {
      return createRecipe();
    }

    V1PersistentVolume createRecipe() {
      Map<String, String> labels = new HashMap<>();
      labels.put(CREATEDBYOPERATOR_LABEL, "true");
      labels.put(DOMAINUID_LABEL, info.getDomainUid());
      return new V1PersistentVolume()
              .metadata(getMetadata().labels(labels))
              .apiVersion(KubernetesConstants.PV_PVC_API_VERSION)
              .spec(createSpec(getSpec()));
    }

    private V1ObjectMeta getMetadata() {
      return Optional.ofNullable(getInitPvDomainPersistentVolume().getMetadata()).orElse(new V1ObjectMeta());
    }

    private PersistentVolumeSpec getSpec() {
      return Optional.ofNullable(getInitPvDomainPersistentVolume().getSpec()).orElse(new PersistentVolumeSpec());
    }

    private V1PersistentVolumeSpec createSpec(PersistentVolumeSpec spec) {
      return new V1PersistentVolumeSpec().accessModes(Collections.singletonList(READ_WRITE_MANY))
          .storageClassName(spec.getStorageClassName())
          .capacity(spec.getCapacity()).persistentVolumeReclaimPolicy(spec.getPersistentVolumeReclaimPolicy())
          .hostPath(spec.getHostPath())
          .nfs(spec.getNfs());
    }


    protected void logPersistentVolumeCreated(String messageKey) {
      LOGGER.info(messageKey, getPersistentVolumeName(), getDomainUid());
    }
  }
}
