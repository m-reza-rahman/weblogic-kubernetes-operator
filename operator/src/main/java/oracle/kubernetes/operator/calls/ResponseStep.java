// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;

import io.kubernetes.client.common.KubernetesType;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import oracle.kubernetes.common.logging.MessageKeys;
import oracle.kubernetes.operator.builders.CallParams;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.DomainCondition;
import oracle.kubernetes.weblogic.domain.model.DomainResource;

import static oracle.kubernetes.operator.KubernetesConstants.HTTP_BAD_METHOD;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_BAD_REQUEST;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_CONFLICT;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_FORBIDDEN;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_GONE;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_INTERNAL_ERROR;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_NOT_FOUND;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_UNAUTHORIZED;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_UNPROCESSABLE_ENTITY;
import static oracle.kubernetes.operator.calls.RequestStep.CONTINUE;
import static oracle.kubernetes.operator.calls.RequestStep.FIBER_TIMEOUT;
import static oracle.kubernetes.operator.calls.RequestStep.accessContinue;
import static oracle.kubernetes.weblogic.domain.model.DomainConditionType.FAILED;
import static oracle.kubernetes.weblogic.domain.model.DomainFailureReason.KUBERNETES;

/**
 * Step to receive response of Kubernetes API server call.
 *
 * <p>Most implementations will only need to implement {@link #onSuccess(Packet, KubernetesApiResponse)}.
 *
 * @param <T> Response type
 */
public abstract class ResponseStep<T extends KubernetesType> extends Step {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private static final Set<Integer> UNRECOVERABLE_ERROR_CODES = Set.of(
      HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_NOT_FOUND,
      HTTP_BAD_METHOD, HTTP_GONE, HTTP_UNPROCESSABLE_ENTITY, HTTP_INTERNAL_ERROR);

  public static boolean isUnrecoverable(KubernetesApiResponse<?> r) {
    return UNRECOVERABLE_ERROR_CODES.contains(r.getHttpStatusCode());
  }

  public static boolean isNotFound(KubernetesApiResponse<?> r) {
    int code = r.getHttpStatusCode();
    return code == HTTP_NOT_FOUND || code == HTTP_GONE;
  }

  public static boolean hasConflict(KubernetesApiResponse<?> r) {
    return r.getHttpStatusCode() == HTTP_CONFLICT;
  }

  public static boolean isForbidden(KubernetesApiResponse<?> r) {
    return r.getHttpStatusCode() == HTTP_FORBIDDEN;
  }

  public static boolean isNotAuthorizedOrForbidden(KubernetesApiResponse<?> r) {
    return r.getHttpStatusCode() == HTTP_UNAUTHORIZED || r.getHttpStatusCode() == HTTP_FORBIDDEN;
  }

  private final Step conflictStep;
  private Step previousStep = null;

  /** Constructor specifying no next step. */
  protected ResponseStep() {
    this(null);
  }

  /**
   * Constructor specifying next step.
   *
   * @param nextStep Next step
   */
  protected ResponseStep(Step nextStep) {
    this(null, nextStep);
  }

  /**
   * Constructor specifying conflict and next step.
   *
   * @param conflictStep Conflict step
   * @param nextStep Next step
   */
  protected ResponseStep(Step conflictStep, Step nextStep) {
    super(nextStep);
    this.conflictStep = conflictStep;
  }

  public final void setPrevious(Step previousStep) {
    this.previousStep = previousStep;
  }

  @Override
  public final Void apply(Packet packet) {
    // HERE
    // Make call
    // If failure, get retry strategy
    // return doRetryOrFailure(conflictStep, retryStep, noRetryFunction, packet, response)
    // clean-up packet
    // return doSuccess


    KubernetesApiResponse<T> response = packet.getSpi(KubernetesApiResponse.class);
    if (response == null || !response.isSuccess()) {
      return doPotentialRetry(conflictStep, packet, response);
    }


    Void nextAction = getActionForKubernetesApiResponse(packet);

    if (nextAction == null) { // no call response, since call timed-out
      nextAction = getPotentialRetryAction(packet);
    }

    if (previousStep != nextAction.getNext()) { // not a retry, clear out old response
      packet.remove(CONTINUE);
      packet.getComponents().remove(RequestStep.RESPONSE_COMPONENT_NAME);
    }

    return nextAction;
  }

  @SuppressWarnings("unchecked")
  /**
   * Returns next action that can be used to get the next batch of results from a list search that
   * specified a "continue" value, if any; otherwise, returns next.
   *
   * @param callResponse Call response
   * @param packet Packet
   * @return Next action for list continue
   */
  protected final Void doContinueListOrNext(KubernetesApiResponse<T> callResponse, Packet packet) {
    return doContinueListOrNext(callResponse, packet, getNext());
  }

  /**
   * Returns next action that can be used to get the next batch of results from a list search that
   * specified a "continue" value, if any; otherwise, returns next.
   *
   * @param callResponse Call response
   * @param packet Packet
   * @param next Next step, if no continuation
   * @return Next action for list continue
   */
  protected final Void doContinueListOrNext(KubernetesApiResponse<T> callResponse, Packet packet, Step next) {
    String cont = accessContinue(callResponse.getObject());
    if (cont != null) {
      packet.put(CONTINUE, cont);
      // Since the continue value is present, invoking the original request will return
      // the next window of data.
      return resetRetryStrategyAndReinvokeRequest(packet);
    }
    return doNext(next, packet);
  }

  /**
   * Returns next action when the Kubernetes API server call should be retried, null otherwise.
   *
   * @param conflictStep Conflict step
   * @param packet Packet
   * @param callResponse the response from the call
   * @return Next action for retry or null, if no retry is warranted
   */
  private Void doPotentialRetry(Step conflictStep, Packet packet, KubernetesApiResponse<T> callResponse) {
    return Optional.ofNullable(packet.getSpi(RetryStrategy.class))
        .map(rs -> rs.doPotentialRetry(conflictStep, packet,
            Optional.ofNullable(callResponse).map(KubernetesApiResponse::getHttpStatusCode).orElse(FIBER_TIMEOUT)))
        .orElseGet(() -> logNoRetry(packet, callResponse));
  }

  private void addDomainFailureStatus(Packet packet, RequestParams requestParams, ApiException apiException) {
    DomainPresenceInfo.fromPacket(packet)
        .map(DomainPresenceInfo::getDomain)
        .ifPresent(domain -> updateFailureStatus(domain, requestParams, apiException));
  }

  private void updateFailureStatus(
      @Nonnull DomainResource domain, RequestParams requestParams, ApiException apiException) {
    DomainCondition condition = new DomainCondition(FAILED).withReason(KUBERNETES)
        .withMessage(createMessage(requestParams, apiException));
    addFailureStatus(domain, condition);
  }

  private void addFailureStatus(@Nonnull DomainResource domain, DomainCondition condition) {
    domain.getOrCreateStatus().addCondition(condition);
  }

  private String createMessage(RequestParams requestParams, ApiException apiException) {
    return requestParams.createFailureMessage(apiException);
  }

  /**
   * Resets any retry strategy, such as a failed retry count and invokes the request again. This
   * will be useful for patterns such as list requests that include a "continue" value.
   * @param packet Packet
   * @return Next action for the original request
   */
  private Void resetRetryStrategyAndReinvokeRequest(Packet packet) {
    RetryStrategy retryStrategy = packet.getSpi(RetryStrategy.class);
    if (retryStrategy != null) {
      retryStrategy.reset();
    }
    return doNext(previousStep, packet);
  }

  /**
   * Callback for API server call failure. The ApiException, HTTP status code and response headers
   * are provided in callResponse; however, these will be null or 0 when the client timed-out.
   *
   * @param packet Packet
   * @param callResponse the result of the call
   * @return Next action for fiber processing, which may be a retry
   */
  public Void onFailure(Packet packet, KubernetesApiResponse<T> callResponse) {
    return onFailure(null, packet, callResponse);
  }

  /**
   * Callback for API server call failure. The ApiException and HTTP status code and response
   * headers are provided; however, these will be null or 0 when the client simply timed-out.
   *
   * <p>The default implementation tests if the request could be retried and, if not, ends fiber
   * processing.
   *
   * @param conflictStep Conflict step
   * @param packet Packet
   * @param callResponse the result of the call
   * @return Next action for fiber processing, which may be a retry
   */
  public Void onFailure(Step conflictStep, Packet packet, KubernetesApiResponse<T> callResponse) {
    return Optional.ofNullable(doPotentialRetry(conflictStep, packet, callResponse))
          .orElseGet(() -> onFailureNoRetry(packet, callResponse));
  }

  protected Void onFailureNoRetry(Packet packet, KubernetesApiResponse<T> callResponse) {
    return doTerminate(createTerminationException(packet, callResponse), packet);
  }

  /**
   * Create an exception to be passed to the doTerminate call.
   *
   * @param packet Packet for creating the exception
   * @param callResponse KubernetesApiResponse for creating the exception
   * @return An Exception to be passed to the doTerminate call
   */
  protected Throwable createTerminationException(Packet packet, KubernetesApiResponse<T> callResponse) {
    return UnrecoverableErrorBuilder.createExceptionFromFailedCall(callResponse);
  }

  /**
   * Callback for API server call success.
   *
   * @param packet Packet
   * @param callResponse the result of the call
   * @return Next action for fiber processing
   */
  public Void onSuccess(Packet packet, KubernetesApiResponse<T> callResponse) {
    throw new IllegalStateException("Must be overridden, if called");
  }
}
