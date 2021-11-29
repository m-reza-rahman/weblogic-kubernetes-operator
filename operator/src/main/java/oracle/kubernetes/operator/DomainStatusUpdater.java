// Copyright (c) 2018, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import jakarta.json.Json;
import jakarta.json.JsonPatchBuilder;
import oracle.kubernetes.operator.calls.CallResponse;
import oracle.kubernetes.operator.calls.FailureStatusSource;
import oracle.kubernetes.operator.calls.UnrecoverableErrorBuilder;
import oracle.kubernetes.operator.helpers.CallBuilder;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.helpers.EventHelper;
import oracle.kubernetes.operator.helpers.EventHelper.EventData;
import oracle.kubernetes.operator.helpers.PodHelper;
import oracle.kubernetes.operator.helpers.ResponseStep;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.rest.Scan;
import oracle.kubernetes.operator.rest.ScanCache;
import oracle.kubernetes.operator.steps.DefaultResponseStep;
import oracle.kubernetes.operator.wlsconfig.WlsClusterConfig;
import oracle.kubernetes.operator.wlsconfig.WlsDomainConfig;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.ClusterStatus;
import oracle.kubernetes.weblogic.domain.model.Configuration;
import oracle.kubernetes.weblogic.domain.model.Domain;
import oracle.kubernetes.weblogic.domain.model.DomainCondition;
import oracle.kubernetes.weblogic.domain.model.DomainSpec;
import oracle.kubernetes.weblogic.domain.model.DomainStatus;
import oracle.kubernetes.weblogic.domain.model.Model;
import oracle.kubernetes.weblogic.domain.model.OnlineUpdate;
import oracle.kubernetes.weblogic.domain.model.ServerHealth;
import oracle.kubernetes.weblogic.domain.model.ServerStatus;
import org.jetbrains.annotations.Nullable;

import static oracle.kubernetes.operator.DomainFailureReason.DomainInvalid;
import static oracle.kubernetes.operator.DomainFailureReason.Internal;
import static oracle.kubernetes.operator.DomainFailureReason.Introspection;
import static oracle.kubernetes.operator.DomainFailureReason.Kubernetes;
import static oracle.kubernetes.operator.DomainFailureReason.ReplicasTooHigh;
import static oracle.kubernetes.operator.DomainFailureReason.ServerPod;
import static oracle.kubernetes.operator.DomainFailureReason.TopologyMismatch;
import static oracle.kubernetes.operator.LabelConstants.CLUSTERNAME_LABEL;
import static oracle.kubernetes.operator.MIINonDynamicChangesMethod.CommitUpdateOnly;
import static oracle.kubernetes.operator.ProcessingConstants.DOMAIN_TOPOLOGY;
import static oracle.kubernetes.operator.ProcessingConstants.FATAL_INTROSPECTOR_ERROR;
import static oracle.kubernetes.operator.ProcessingConstants.FATAL_INTROSPECTOR_ERROR_MSG;
import static oracle.kubernetes.operator.ProcessingConstants.INTROSPECTION_ERROR;
import static oracle.kubernetes.operator.ProcessingConstants.MII_DYNAMIC_UPDATE;
import static oracle.kubernetes.operator.ProcessingConstants.MII_DYNAMIC_UPDATE_RESTART_REQUIRED;
import static oracle.kubernetes.operator.ProcessingConstants.SERVER_HEALTH_MAP;
import static oracle.kubernetes.operator.ProcessingConstants.SERVER_STATE_MAP;
import static oracle.kubernetes.operator.WebLogicConstants.RUNNING_STATE;
import static oracle.kubernetes.operator.WebLogicConstants.SHUTDOWN_STATE;
import static oracle.kubernetes.operator.WebLogicConstants.SHUTTING_DOWN_STATE;
import static oracle.kubernetes.operator.helpers.EventHelper.EventItem.DOMAIN_AVAILABLE;
import static oracle.kubernetes.operator.helpers.EventHelper.EventItem.DOMAIN_COMPLETE;
import static oracle.kubernetes.operator.helpers.EventHelper.EventItem.DOMAIN_FAILED;
import static oracle.kubernetes.operator.helpers.EventHelper.EventItem.DOMAIN_FAILURE_RESOLVED;
import static oracle.kubernetes.operator.helpers.EventHelper.EventItem.DOMAIN_INCOMPLETE;
import static oracle.kubernetes.operator.helpers.EventHelper.EventItem.DOMAIN_UNAVAILABLE;
import static oracle.kubernetes.operator.helpers.EventHelper.createEventStep;
import static oracle.kubernetes.operator.logging.MessageKeys.DOMAIN_FATAL_ERROR;
import static oracle.kubernetes.operator.logging.MessageKeys.INTROSPECTOR_MAX_ERRORS_EXCEEDED;
import static oracle.kubernetes.operator.logging.MessageKeys.TOO_MANY_REPLICAS_FAILURE;
import static oracle.kubernetes.utils.OperatorUtils.onSeparateLines;
import static oracle.kubernetes.weblogic.domain.model.DomainConditionType.Available;
import static oracle.kubernetes.weblogic.domain.model.DomainConditionType.Completed;
import static oracle.kubernetes.weblogic.domain.model.DomainConditionType.ConfigChangesPendingRestart;
import static oracle.kubernetes.weblogic.domain.model.DomainConditionType.Failed;

/**
 * Updates for status of Domain. This class has two modes: 1) Watching for Pod state changes by
 * listening to events from {@link PodWatcher} and 2) Factory for {@link Step}s that the main
 * processing flow can use to explicitly set the condition.
 */
@SuppressWarnings("WeakerAccess")
public class DomainStatusUpdater {

  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");
  private static final String TRUE = "True";
  private static final String FALSE = "False";

  private DomainStatusUpdater() {
  }

  /**
   * Creates an asynchronous step to update domain status from the topology in the current packet.
   *
   * @param next the next step
   * @return the new step
   */
  public static Step createStatusUpdateStep(Step next) {
    return new StatusUpdateStep(next);
  }

  /**
   * Asynchronous step to remove any current failure conditions.
   */
  public static Step createRemoveFailuresStep() {
    return new RemoveFailuresStep();
  }

  /**
   * Asynchronous step to create a failure condition.
   */
  public static Step createFailedStep(DomainFailureReason reason, String message) {
    return new FailedStep(reason, message);
  }

  /**
   * Asynchronous steps to set Domain condition to Failed after an asynchronous call failure
   * and to generate DOMAIN_FAILED event.
   *
   * @param callResponse the response from an unrecoverable call
   */
  public static Step createKubernetesFailureRelatedSteps(CallResponse<?> callResponse) {
    FailureStatusSource failure = UnrecoverableErrorBuilder.fromFailedCall(callResponse);

    LOGGER.severe(MessageKeys.CALL_FAILED, failure.getMessage(), failure.getReason());
    ApiException apiException = callResponse.getE();
    if (apiException != null) {
      LOGGER.fine(MessageKeys.EXCEPTION, apiException);
    }

    return createFailureRelatedSteps(Kubernetes, failure.getMessage());
  }

  /**
   * Asynchronous steps to set Domain condition to Failed and to generate DOMAIN_FAILED event.
   *
   * @param throwable Throwable that caused failure
   */
  static Step createInternalFailureRelatedSteps(Throwable throwable, V1Job domainIntrospectorJob) {
    String message = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();

    return Step.chain(
        new FailedStep(Internal, message),
        createFailureCountStep(domainIntrospectorJob),
        createEventStep(new EventData(DOMAIN_FAILED, message).failureReason(Internal)));
  }

  /**
   * Asynchronous steps to set Domain condition to Failed and to generate DOMAIN_FAILED event.
   *
   * @param message a fuller description of the problem
   */
  public static Step createServerPodFailureRelatedSteps(String message) {
    return createFailureRelatedSteps(ServerPod, message);
  }

  /**
   * Asynchronous steps to set Domain condition to Failed and to generate DOMAIN_FAILED event.
   *
   * @param message a fuller description of the problem
   */
  public static Step createDomainInvalidFailureRelatedSteps(String message) {
    return createFailureRelatedSteps(DomainInvalid, message);
  }

  /**
   * Asynchronous steps to set Domain condition to Failed and to generate DOMAIN_FAILED event.
   *
   * @param message a fuller description of the problem
   */
  public static Step createReplicasTooHighFailureRelatedSteps(String message) {
    return createFailureRelatedSteps(ReplicasTooHigh, message);
  }


  /**
   * Asynchronous steps to set Domain condition to Failed and to generate DOMAIN_FAILED event.
   *
   * @param message a fuller description of the problem
   */
  public static Step createTopologyMismatchFailureRelatedSteps(String message) {
    return createFailureRelatedSteps(TopologyMismatch, message);
  }

  /**
   * Asynchronous steps to set Domain condition to Failed and to generate DOMAIN_FAILED event.
   *
   * @param reason the failure category
   * @param message a fuller description of the problem
   */
  public static Step createFailureRelatedSteps(
      @Nonnull DomainFailureReason reason, String message) {
    return Step.chain(
        createFailedStep(reason, message),
        createEventStep(new EventData(DOMAIN_FAILED, message).failureReason(reason)));
  }

  /**
   * Asynchronous steps to set Domain condition to Failed, increment the introspector failure count if needed
   * and to generate DOMAIN_FAILED event.
   *
   * @param message               a fuller description of the problem
   * @param domainIntrospectorJob Domain introspector job
   */
  public static Step createIntrospectionFailureRelatedSteps(String message,
                                                            V1Job domainIntrospectorJob) {
    return Step.chain(new FailedStep(Introspection, message),
        createFailureCountStep(domainIntrospectorJob),
        createEventStep(new EventData(DOMAIN_FAILED, message).failureReason(Introspection)));
  }

  /**
   * Asynchronous steps to set Domain condition to Failed, increment the introspector failure count if needed
   * and to generate DOMAIN_FAILED event.
   *
   * @param message a fuller description of the problem
   */
  public static Step createIntrospectionFailureRelatedSteps(String message) {
    return
        Step.chain(
            new FailedStep(Introspection, message),
            createEventStep(new EventData(DOMAIN_FAILED, message).failureReason(Introspection)));
  }

  public static Step createFailureCountStep(V1Job domainIntrospectorJob) {
    return new FailureCountStep(domainIntrospectorJob);
  }

  static class FailureCountStep extends DomainStatusUpdaterStep {

    private final V1Job domainIntrospectorJob;

    public FailureCountStep(V1Job domainIntrospectorJob) {
      super(null);
      this.domainIntrospectorJob = domainIntrospectorJob;
    }

    @Override
    void modifyStatus(DomainStatus domainStatus) {
      domainStatus.incrementIntrospectJobFailureCount(getJobUid());
      addRetryInfoToStatusMessage(domainStatus);
    }

    @Nullable
    private String getJobUid() {
      return Optional.ofNullable(domainIntrospectorJob).map(V1Job::getMetadata).map(V1ObjectMeta::getUid).orElse(null);
    }

    private void addRetryInfoToStatusMessage(DomainStatus domainStatus) {

      if (hasExceededMaxRetryCount(domainStatus)) {
        domainStatus.setMessage(createStatusMessage(domainStatus, exceededMaxRetryCountErrorMessage()));
      } else if (isFatalError(domainStatus)) {
        domainStatus.setMessage(createStatusMessage(domainStatus, LOGGER.formatMessage(DOMAIN_FATAL_ERROR)));
      } else {
        domainStatus.setMessage(createStatusMessage(domainStatus, getNonFatalRetryStatusMessage(domainStatus)));
      }
    }

    private boolean hasExceededMaxRetryCount(DomainStatus domainStatus) {
      return domainStatus.getIntrospectJobFailureCount() >= getFailureRetryMaxCount();
    }

    private String createStatusMessage(DomainStatus domainStatus, String retryStatusMessage) {
      return onSeparateLines(retryStatusMessage, INTROSPECTION_ERROR, domainStatus.getMessage());
    }

    private String getNonFatalRetryStatusMessage(DomainStatus domainStatus) {
      return LOGGER.formatMessage(MessageKeys.NON_FATAL_INTROSPECTOR_ERROR,
          domainStatus.getIntrospectJobFailureCount(), getFailureRetryMaxCount());
    }

    private boolean isFatalError(DomainStatus domainStatus) {
      return Optional.ofNullable(domainStatus.getMessage())
          .map(m -> m.contains(FATAL_INTROSPECTOR_ERROR)).orElse(false);
    }

    private String exceededMaxRetryCountErrorMessage() {
      return LOGGER.formatMessage(INTROSPECTOR_MAX_ERRORS_EXCEEDED, getFailureRetryMaxCount());
    }
  }

  private static int getFailureRetryMaxCount() {
    return DomainPresence.getDomainPresenceFailureRetryMaxCount();
  }

  static class ResetFailureCountStep extends DomainStatusUpdaterStep {

    @Override
    void modifyStatus(DomainStatus domainStatus) {
      domainStatus.resetIntrospectJobFailureCount();
    }
  }

  public static Step createResetFailureCountStep() {
    return new ResetFailureCountStep();
  }

  abstract static class DomainStatusUpdaterStep extends Step {

    DomainStatusUpdaterStep() {
    }

    DomainStatusUpdaterStep(Step next) {
      super(next);
    }

    DomainStatusUpdaterContext createContext(Packet packet, Step retryStep) {
      return new DomainStatusUpdaterContext(packet, this);
    }

    abstract void modifyStatus(DomainStatus domainStatus);

    @Override
    public NextAction apply(Packet packet) {
      DomainStatusUpdaterContext context = createContext(packet, this);

      return context.isStatusUnchanged()
          ? doNext(packet)
          : doNext(context.createUpdateSteps(),
          packet);
    }

    private ResponseStep<Domain> createResponseStep(DomainStatusUpdaterContext context) {
      return new StatusReplaceResponseStep(this, context, getNext());
    }
  }

  static class StatusReplaceResponseStep extends DefaultResponseStep<Domain> {
    private final DomainStatusUpdaterStep updaterStep;
    private final DomainStatusUpdaterContext context;

    public StatusReplaceResponseStep(DomainStatusUpdaterStep updaterStep,
                                     DomainStatusUpdaterContext context, Step nextStep) {
      super(nextStep);
      this.updaterStep = updaterStep;
      this.context = context;
    }

    @Override
    public NextAction onSuccess(Packet packet, CallResponse<Domain> callResponse) {
      if (callResponse.getResult() != null) {
        packet.getSpi(DomainPresenceInfo.class).setDomain(callResponse.getResult());
      }
      return doNext(packet);
    }

    @Override
    public NextAction onFailure(Packet packet, CallResponse<Domain> callResponse) {
      if (UnrecoverableErrorBuilder.isAsyncCallUnrecoverableFailure(callResponse)) {
        return super.onFailure(packet, callResponse);
      } else {
        return onFailure(createRetry(context), packet, callResponse);
      }
    }

    public Step createRetry(DomainStatusUpdaterContext context) {
      return Step.chain(createDomainRefreshStep(context), updaterStep);
    }

    private Step createDomainRefreshStep(DomainStatusUpdaterContext context) {
      return new CallBuilder().readDomainAsync(context.getDomainName(), context.getNamespace(), new DomainUpdateStep());
    }
  }

  static class DomainUpdateStep extends ResponseStep<Domain> {
    @Override
    public NextAction onSuccess(Packet packet, CallResponse<Domain> callResponse) {
      packet.getSpi(DomainPresenceInfo.class).setDomain(callResponse.getResult());
      return doNext(packet);
    }
  }

  static class DomainStatusUpdaterContext {
    private final DomainPresenceInfo info;
    private final DomainStatusUpdaterStep domainStatusUpdaterStep;

    DomainStatusUpdaterContext(Packet packet, DomainStatusUpdaterStep domainStatusUpdaterStep) {
      info = packet.getSpi(DomainPresenceInfo.class);
      this.domainStatusUpdaterStep = domainStatusUpdaterStep;
    }

    DomainStatus getNewStatus() {
      DomainStatus newStatus = cloneStatus();
      modifyStatus(newStatus);

      if (newStatus.getMessage() == null) {
        newStatus.setMessage(
            Optional.ofNullable(info).map(DomainPresenceInfo::getValidationWarningsAsString).orElse(null));
      }

      return newStatus;
    }

    String getDomainUid() {
      return getDomain().getDomainUid();
    }

    boolean isStatusUnchanged() {
      return getDomain() == null || getNewStatus().equals(getStatus());
    }

    private String getNamespace() {
      return getMetadata().getNamespace();
    }

    private V1ObjectMeta getMetadata() {
      return getDomain().getMetadata();
    }

    DomainPresenceInfo getInfo() {
      return info;
    }

    DomainStatus getStatus() {
      return getDomain().getStatus();
    }

    Domain getDomain() {
      return info.getDomain();
    }

    void modifyStatus(DomainStatus status) {
      domainStatusUpdaterStep.modifyStatus(status);
    }

    private String getDomainName() {
      return getMetadata().getName();
    }

    DomainStatus cloneStatus() {
      return Optional.ofNullable(getStatus()).map(DomainStatus::new).orElse(new DomainStatus());
    }

    private Step createDomainStatusReplaceStep() {
      LOGGER.fine(MessageKeys.DOMAIN_STATUS, getDomainUid(), getNewStatus());
      if (LOGGER.isFinerEnabled()) {
        LOGGER.finer("status change: " + createPatchString());
      }
      Domain oldDomain = getDomain();
      Domain newDomain = new Domain()
          .withKind(KubernetesConstants.DOMAIN)
          .withApiVersion(KubernetesConstants.API_VERSION_WEBLOGIC_ORACLE)
          .withMetadata(oldDomain.getMetadata())
          .withSpec(null)
          .withStatus(getNewStatus());

      return new CallBuilder().replaceDomainStatusAsync(
          getDomainName(),
          getNamespace(),
          newDomain,
          domainStatusUpdaterStep.createResponseStep(this));
    }

    private String createPatchString() {
      JsonPatchBuilder builder = Json.createPatchBuilder();
      getNewStatus().createPatchFrom(builder, getStatus());
      return builder.build().toString();
    }

    private Step createUpdateSteps() {
      final Step next = createDomainStatusReplaceStep();
      List<EventData> eventDataList = createDomainEvents();
      return eventDataList.isEmpty() ? next : Step.chain(createEventSteps(eventDataList), next);
    }

    private Step createEventSteps(List<EventData> eventDataList) {
      return Step.chain(
          eventDataList.stream()
              .sorted(Comparator.comparing(EventData::getItem))
              .map(EventHelper::createEventStep)
              .toArray(Step[]::new)
      );
    }

    @Nonnull
    List<EventData> createDomainEvents() {
      List<EventData> list = new ArrayList<>();
      if (hasJustExceededMaxRetryCount()) {
        list.add(
            new EventData(EventHelper.EventItem.DOMAIN_FAILED).message(INTROSPECTOR_MAX_ERRORS_EXCEEDED));
      } else if (hasJustGotFatalIntrospectorError()) {
        list.add(new EventData(EventHelper.EventItem.DOMAIN_FAILED)
            .message(FATAL_INTROSPECTOR_ERROR_MSG + getNewStatus().getMessage()));
      }
      return list;
    }

    private boolean hasJustGotFatalIntrospectorError() {
      return isFatalIntrospectorMessage(getNewStatus().getMessage())
          && !isFatalIntrospectorMessage(getStatus().getMessage());
    }

    private boolean isFatalIntrospectorMessage(String statusMessage) {
      return statusMessage != null && statusMessage.contains(FATAL_INTROSPECTOR_ERROR);
    }

    private boolean hasJustExceededMaxRetryCount() {
      return getStatus() != null
          && getNewStatus().getIntrospectJobFailureCount() == (getStatus().getIntrospectJobFailureCount() + 1)
          && getNewStatus().getIntrospectJobFailureCount() >= getFailureRetryMaxCount();
    }

  }

  /**
   * A step which updates the domain status from the domain topology in the current packet.
   */
  private static class StatusUpdateStep extends DomainStatusUpdaterStep {
    StatusUpdateStep(Step next) {
      super(next);
    }

    @Override
    DomainStatusUpdaterContext createContext(Packet packet, Step retryStep) {
      return new StatusUpdateContext(packet, this);
    }

    @Override
    void modifyStatus(DomainStatus domainStatus) { // no-op; modification happens in the context itself.
    }

    static class StatusUpdateContext extends DomainStatusUpdaterContext {
      private final WlsDomainConfig config;
      private final Set<String> expectedRunningServers;
      private final Map<String, String> serverState;
      private final Map<String, ServerHealth> serverHealth;
      private final Packet packet;

      StatusUpdateContext(Packet packet, StatusUpdateStep statusUpdateStep) {
        super(packet, statusUpdateStep);
        this.packet = packet;
        config = packet.getValue(DOMAIN_TOPOLOGY);
        serverState = packet.getValue(SERVER_STATE_MAP);
        serverHealth = packet.getValue(SERVER_HEALTH_MAP);
        expectedRunningServers = DomainPresenceInfo.fromPacket(packet)
            .map(DomainPresenceInfo::getExpectedRunningServers)
            .orElse(Collections.emptySet());
      }

      @Override
      void modifyStatus(DomainStatus status) {
        if (getDomain() != null) {
          setStatusConditions(status);
          if (getDomainConfig().isPresent()) {
            setStatusDetails(status);
          }
        }
      }

      @Nonnull
      @Override
      List<EventData> createDomainEvents() {
        List<EventData> list = new ArrayList<>();
        if (domainFailureJustResolved()) {
          list.add(new EventData(DOMAIN_FAILURE_RESOLVED));
        }
        if (domainJustAvailable()) {
          list.add(new EventData(DOMAIN_AVAILABLE));
        } else if (domainJustUnavailable()) {
          list.add(new EventData(DOMAIN_UNAVAILABLE));
        }
        if (processingJustCompleted()) {
          list.add(new EventData(DOMAIN_COMPLETE));
        } else if (domainJustIncomplete()) {
          list.add(new EventData(DOMAIN_INCOMPLETE));
        }
        Optional.ofNullable(createTooManyReplicasFailuresEventDataIfNeeded()).map(e -> list.add(e));
        return list;
      }

      private boolean processingJustCompleted() {
        return allIntendedServersRunning() && !oldStatusWasCompleted();
      }

      private boolean domainFailureJustResolved() {
        return !newStatusIsFailed() && oldStatusWasFailed();
      }

      private boolean oldStatusWasFailed() {
        return getStatus() != null && getStatus().hasConditionWith(this::isDomainFailed);
      }

      private boolean newStatusIsFailed() {
        return getNewStatus() != null && getNewStatus().hasConditionWith(this::isDomainFailed);
      }

      private boolean domainJustIncomplete() {
        return !allIntendedServersRunning() && oldStatusWasCompleted();
      }

      private boolean oldStatusWasCompleted() {
        return getStatus() != null && getStatus().hasConditionWith(this::isDomainCompleted);
      }

      private boolean oldStatusWasAvailable() {
        return getStatus() != null && getStatus().hasConditionWith(this::isDomainAvailable);
      }

      private boolean domainJustAvailable() {
        return sufficientServersRunning() && !oldStatusWasAvailable();
      }

      private boolean domainJustUnavailable() {
        return !sufficientServersRunning() && oldStatusWasAvailable();
      }

      private void setStatusConditions(DomainStatus status) {
        if (allIntendedServersRunning()) {
          status.removeConditionWithType(Failed);
          status.addCondition(new DomainCondition(Completed).withStatus(TRUE));
          status.addCondition(new DomainCondition(Available).withStatus(TRUE));
        } else {
          status.addCondition(new DomainCondition(Completed).withStatus(FALSE));
          if (sufficientServersRunning()) {
            status.addCondition(new DomainCondition(Available).withStatus(TRUE));
          } else if (status.hasConditionWithType(Available)) {
            status.addCondition(new DomainCondition(Available).withStatus(FALSE));
          }
          addTooManyReplicasFailures(status);
        }

        if (isHasFailedPod()) {
          status.addCondition(new DomainCondition(Failed).withStatus(TRUE).withReason(ServerPod));
        } else if (allIntendedServersRunning()) {
          if (!stillHasPodPendingRestart(status)
              && status.hasConditionWithType(ConfigChangesPendingRestart)) {
            status.removeConditionWithType(ConfigChangesPendingRestart);
          }
        }

        if (miiNondynamicRestartRequired() && isCommitUpdateOnly()) {
          setOnlineUpdateNeedRestartCondition(status);
        }
      }

      private boolean isDomainFailed(DomainCondition condition) {
        return condition.hasType(Failed) && condition.getStatus().equals("True");
      }

      private boolean isDomainFailedWithReplicasTooHigh(DomainCondition condition) {
        return condition.hasType(Failed) && condition.getStatus().equals("True")
            && ReplicasTooHigh.equals(condition.getReason());
      }

      private boolean isDomainCompleted(DomainCondition condition) {
        return condition.hasType(Completed) && condition.getStatus().equals("True");
      }


      private boolean isDomainAvailable(DomainCondition condition) {
        return condition.hasType(Available) && condition.getStatus().equals("True");
      }

      private void setStatusDetails(DomainStatus status) {
        status.setServers(new ArrayList<>(getServerStatuses(getAdminServerName()).values()));
        status.setClusters(new ArrayList<>(getClusterStatuses().values()));
        status.setReplicas(getReplicaSetting());
      }

      @Nonnull
      private String getAdminServerName() {
        return getDomainConfig().map(WlsDomainConfig::getAdminServerName).orElse("");
      }

      private boolean miiNondynamicRestartRequired() {
        return MII_DYNAMIC_UPDATE_RESTART_REQUIRED.equals(packet.get(MII_DYNAMIC_UPDATE));
      }

      private boolean isCommitUpdateOnly() {
        return getMiiNonDynamicChangesMethod() == CommitUpdateOnly;
      }

      private MIINonDynamicChangesMethod getMiiNonDynamicChangesMethod() {
        return DomainPresenceInfo.fromPacket(packet)
            .map(DomainPresenceInfo::getDomain)
            .map(Domain::getSpec)
            .map(DomainSpec::getConfiguration)
            .map(Configuration::getModel)
            .map(Model::getOnlineUpdate)
            .map(OnlineUpdate::getOnNonDynamicChanges)
            .orElse(MIINonDynamicChangesMethod.CommitUpdateOnly);
      }

      private void setOnlineUpdateNeedRestartCondition(DomainStatus status) {
        String dynamicUpdateRollBackFile = Optional.ofNullable((String) packet.get(
                ProcessingConstants.MII_DYNAMIC_UPDATE_WDTROLLBACKFILE))
            .orElse("");
        String message = String.format("%s\n%s",
            LOGGER.formatMessage(MessageKeys.MII_DOMAIN_UPDATED_POD_RESTART_REQUIRED), dynamicUpdateRollBackFile);
        updateDomainConditions(status, message);
      }

      private void updateDomainConditions(DomainStatus status, String message) {
        DomainCondition onlineUpdateCondition = new DomainCondition(ConfigChangesPendingRestart)
            .withMessage(message)
            .withStatus("True");

        status.removeConditionWithType(ConfigChangesPendingRestart);
        status.addCondition(onlineUpdateCondition);
      }

      private boolean stillHasPodPendingRestart(DomainStatus status) {
        return status.getServers().stream()
            .map(this::getServerPod)
            .map(this::getLabels)
            .anyMatch(m -> m.containsKey(LabelConstants.MII_UPDATED_RESTART_REQUIRED_LABEL));
      }

      private V1Pod getServerPod(ServerStatus serverStatus) {
        return getInfo().getServerPod(serverStatus.getServerName());
      }

      private Map<String, String> getLabels(V1Pod pod) {
        return Optional.ofNullable(pod)
            .map(V1Pod::getMetadata)
            .map(V1ObjectMeta::getLabels)
            .orElse(Collections.emptyMap());
      }

      private boolean allIntendedServersRunning() {
        return atLeastOneApplicationServerStarted()
            && expectedRunningServers.stream().noneMatch(this::isNotRunning)
            && expectedRunningServers.containsAll(serverState.keySet())
            && serversMarkedForRoll().isEmpty()
            && noReplicasTooHighFailure();
      }

      private boolean noReplicasTooHighFailure() {
        return getStatus() == null || !getStatus().hasConditionWith(this::isDomainFailedWithReplicasTooHigh);
      }

      private boolean atLeastOneApplicationServerStarted() {
        return getInfo().getServerStartupInfo().size() > 0;
      }

      private boolean sufficientServersRunning() {
        return atLeastOneApplicationServerStarted() && allNonClusteredServersRunning() && allClustersAvailable();
      }

      private @Nonnull List<String> getNonClusteredServers() {
        return expectedRunningServers.stream().filter(this::isNonClusteredServer).collect(Collectors.toList());
      }

      private boolean allNonClusteredServersRunning() {
        return getNonClusteredServers().stream().noneMatch(this::isNotRunning);
      }

      private boolean allClustersAvailable() {
        return getClusterNames().stream().allMatch(this::isAvailable);
      }

      private boolean isAvailable(String clusterName) {
        return isClusterIntentionallyShutDown(clusterName) || sufficientServersInClusterRunning(clusterName);
      }

      private boolean sufficientServersInClusterRunning(String clusterName) {
        return clusterHasRunningServer(clusterName)
            && numServersInClusterNotReady(clusterName) <= maxUnavailable(clusterName);
      }

      private boolean isClusterIntentionallyShutDown(String clusterName) {
        return getStartedServersInCluster(clusterName).isEmpty();
      }

      private boolean clusterHasRunningServer(String clusterName) {
        return getStartedServersInCluster(clusterName).stream().anyMatch(this::isRunning);
      }

      private long numServersInClusterNotReady(String clusterName) {
        return getStartedServersInCluster(clusterName).stream().filter(this::isNotRunning).count();
      }

      private List<String> getStartedServersInCluster(String clusterName) {
        return expectedRunningServers.stream()
            .filter(server -> clusterName.equals(getClusterName(server)))
            .collect(Collectors.toList());
      }

      private int maxUnavailable(String clusterName) {
        return getDomain().getMaxUnavailable(clusterName);
      }

      private Set<String> serversMarkedForRoll() {
        return DomainPresenceInfo.fromPacket(packet)
            .map(DomainPresenceInfo::getServersToRoll)
            .map(Map::keySet)
            .orElse(Collections.emptySet());
      }

      private boolean isNonClusteredServer(String serverName) {
        return getClusterName(serverName) == null;
      }

      private Optional<WlsDomainConfig> getDomainConfig() {
        return Optional.ofNullable(config).or(this::getScanCacheDomainConfig);
      }

      private Optional<WlsDomainConfig> getScanCacheDomainConfig() {
        DomainPresenceInfo info = getInfo();
        Scan scan = ScanCache.INSTANCE.lookupScan(info.getNamespace(), info.getDomainUid());
        return Optional.ofNullable(scan).map(Scan::getWlsDomainConfig);
      }

      private boolean isRunning(@Nonnull String serverName) {
        return RUNNING_STATE.equals(getRunningState(serverName));
      }

      private boolean isNotRunning(@Nonnull String serverName) {
        return !RUNNING_STATE.equals(getRunningState(serverName));
      }

      private boolean isHasFailedPod() {
        return getInfo().getServerPods().anyMatch(PodHelper::isFailed);
      }

      private void addTooManyReplicasFailures(DomainStatus status) {
        getConfiguredClusters().stream()
            .map(TooManyReplicasCheck::new)
            .filter(TooManyReplicasCheck::isFailure)
            .forEach(check -> status.addCondition(check.createFailureCondition()));
      }

      private EventData createTooManyReplicasFailuresEventDataIfNeeded() {
        return getConfiguredClusters().stream()
            .map(TooManyReplicasCheck::new)
            .filter(TooManyReplicasCheck::isFailure)
            .findAny().map(check -> check.creatEventData()).orElse(null);
      }

      private List<WlsClusterConfig> getConfiguredClusters() {
        return Optional.ofNullable(config).map(WlsDomainConfig::getConfiguredClusters).orElse(Collections.emptyList());
      }

      private class TooManyReplicasCheck {
        private final String clusterName;
        private final int maxReplicaCount;
        private final int specifiedReplicaCount;

        TooManyReplicasCheck(WlsClusterConfig cluster) {
          clusterName = cluster.getClusterName();
          maxReplicaCount = cluster.getMaxDynamicClusterSize();
          specifiedReplicaCount = getDomain().getReplicaCount(clusterName);
        }

        private boolean isFailure() {
          return maxReplicaCount > 0 && specifiedReplicaCount > maxReplicaCount;
        }

        private DomainCondition createFailureCondition() {
          return new DomainCondition(Failed).withReason(ReplicasTooHigh).withMessage(createFailureMessage());
        }

        private String createFailureMessage() {
          return LOGGER.formatMessage(TOO_MANY_REPLICAS_FAILURE, specifiedReplicaCount, clusterName, maxReplicaCount);
        }

        private EventData creatEventData() {
          return new EventData(DOMAIN_FAILED, createFailureMessage()).failureReason(ReplicasTooHigh);
        }
      }

      private boolean hasServerPod(String serverName) {
        return Optional.ofNullable(getInfo().getServerPod(serverName)).isPresent();
      }

      private boolean hasReadyServerPod(String serverName) {
        return Optional.ofNullable(getInfo().getServerPod(serverName)).filter(PodHelper::getReadyStatus).isPresent();
      }

      Map<String, ServerStatus> getServerStatuses(final String adminServerName) {
        return getServerNames().stream()
            .collect(Collectors.toMap(Function.identity(),
                s -> createServerStatus(s, Objects.equals(s, adminServerName))));
      }

      private ServerStatus createServerStatus(String serverName, boolean isAdminServer) {
        String clusterName = getClusterName(serverName);
        return new ServerStatus()
            .withServerName(serverName)
            .withState(getRunningState(serverName))
            .withDesiredState(getDesiredState(serverName, clusterName, isAdminServer))
            .withHealth(serverHealth == null ? null : serverHealth.get(serverName))
            .withClusterName(clusterName)
            .withNodeName(getNodeName(serverName))
            .withIsAdminServer(isAdminServer);
      }

      private String getRunningState(String serverName) {
        if (!getInfo().getServerNames().contains(serverName)) {
          return SHUTDOWN_STATE;
        } else if (isDeleting(serverName)) {
          return SHUTTING_DOWN_STATE;
        } else {
          return Optional.ofNullable(serverState).map(m -> m.get(serverName)).orElse(null);
        }
      }

      private boolean isDeleting(String serverName) {
        return Optional.ofNullable(getInfo().getServerPod(serverName)).map(PodHelper::isDeleting).orElse(false);
      }

      private String getDesiredState(String serverName, String clusterName, boolean isAdminServer) {
        return isAdminServer | expectedRunningServers.contains(serverName)
            ? getDomain().getServer(serverName, clusterName).getDesiredState()
            : SHUTDOWN_STATE;
      }

      Integer getReplicaSetting() {
        Collection<Long> values = getClusterCounts().values();
        if (values.size() == 1) {
          return values.iterator().next().intValue();
        } else {
          return null;
        }
      }

      private Stream<String> getServers(boolean isReadyOnly) {
        return getServerNames().stream()
            .filter(isReadyOnly ? this::hasReadyServerPod : this::hasServerPod);
      }

      private Map<String, Long> getClusterCounts() {
        return getClusterCounts(false);
      }

      private Map<String, Long> getClusterCounts(boolean isReadyOnly) {
        return getServers(isReadyOnly)
            .map(this::getClusterNameFromPod)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
      }

      Map<String, ClusterStatus> getClusterStatuses() {
        return getClusterNames().stream()
            .collect(Collectors.toMap(Function.identity(), this::createClusterStatus));
      }

      private ClusterStatus createClusterStatus(String clusterName) {
        return new ClusterStatus()
            .withClusterName(clusterName)
            .withReplicas(Optional.ofNullable(getClusterCounts().get(clusterName))
                .map(Long::intValue).orElse(null))
            .withReadyReplicas(
                Optional.ofNullable(getClusterCounts(true)
                    .get(clusterName)).map(Long::intValue).orElse(null))
            .withMaximumReplicas(getClusterMaximumSize(clusterName))
            .withMinimumReplicas(getClusterMinimumSize(clusterName))
            .withReplicasGoal(getClusterSizeGoal(clusterName));
      }

      private String getNodeName(String serverName) {
        return Optional.ofNullable(getInfo().getServerPod(serverName))
            .map(V1Pod::getSpec)
            .map(V1PodSpec::getNodeName)
            .orElse(null);
      }

      private String getClusterName(String serverName) {
        return getDomainConfig()
            .map(c -> c.getClusterName(serverName))
            .orElse(getClusterNameFromPod(serverName));
      }

      private String getClusterNameFromPod(String serverName) {
        return Optional.ofNullable(getInfo().getServerPod(serverName))
            .map(V1Pod::getMetadata)
            .map(V1ObjectMeta::getLabels)
            .map(l -> l.get(CLUSTERNAME_LABEL))
            .orElse(null);
      }

      private Collection<String> getServerNames() {
        Set<String> result = new HashSet<>();
        getDomainConfig()
            .ifPresent(config -> {
              result.addAll(config.getServerConfigs().keySet());
              for (WlsClusterConfig cluster : config.getConfiguredClusters()) {
                Optional.ofNullable(cluster.getDynamicServersConfig())
                    .flatMap(dynamicConfig -> Optional.ofNullable(dynamicConfig.getServerConfigs()))
                    .ifPresent(servers -> servers.forEach(item -> result.add(item.getName())));
                Optional.ofNullable(cluster.getServerConfigs())
                    .ifPresent(servers -> servers.forEach(item -> result.add(item.getName())));
              }
            });
        return result;
      }

      private Collection<String> getClusterNames() {
        Set<String> result = new HashSet<>();
        getDomainConfig().ifPresent(config -> result.addAll(config.getClusterConfigs().keySet()));
        return result;
      }

      private Integer getClusterMaximumSize(String clusterName) {
        return getDomainConfig()
            .map(config -> config.getClusterConfig(clusterName))
            .map(WlsClusterConfig::getMaxClusterSize)
            .orElse(0);
      }

      private Integer getClusterMinimumSize(String clusterName) {
        return getDomain().isAllowReplicasBelowMinDynClusterSize(clusterName)
            ? 0 : getDomainConfig()
            .map(config -> config.getClusterConfig(clusterName))
            .map(WlsClusterConfig::getMinClusterSize)
            .orElse(0);
      }

      private Integer getClusterSizeGoal(String clusterName) {
        return getDomain().getReplicaCount(clusterName);
      }
    }
  }

  public static class RemoveFailuresStep extends DomainStatusUpdaterStep {

    private RemoveFailuresStep() {
    }

    @Override
    void modifyStatus(DomainStatus status) {
      status.removeConditionWithType(Failed);
    }
  }

  private static class FailedStep extends DomainStatusUpdaterStep {
    private final DomainFailureReason reason;
    private final String message;

    private FailedStep(DomainFailureReason reason, String message) {
      super();
      this.reason = reason;
      this.message = message;
    }

    @Override
    void modifyStatus(DomainStatus s) {
      s.addCondition(new DomainCondition(Failed).withStatus(TRUE).withReason(reason).withMessage(message));
    }
  }
}
