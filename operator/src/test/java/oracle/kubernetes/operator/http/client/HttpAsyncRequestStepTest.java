// Copyright (c) 2020, 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.http.client;

import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.annotation.Nonnull;

import com.meterware.simplestub.Memento;
import com.meterware.simplestub.StaticStubSupport;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.tuning.TuningParametersStub;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.meterware.simplestub.Stub.createStub;
import static oracle.kubernetes.common.logging.MessageKeys.HTTP_METHOD_FAILED;
import static oracle.kubernetes.common.logging.MessageKeys.HTTP_REQUEST_GOT_THROWABLE;
import static oracle.kubernetes.common.logging.MessageKeys.HTTP_REQUEST_TIMED_OUT;
import static oracle.kubernetes.common.utils.LogMatcher.containsWarning;
import static oracle.kubernetes.operator.DomainProcessorTestSetup.NS;
import static oracle.kubernetes.operator.DomainProcessorTestSetup.UID;
import static oracle.kubernetes.operator.http.client.HttpResponseStep.RESPONSE;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.typeCompatibleWith;
import static org.hamcrest.junit.MatcherAssert.assertThat;

/**
 * Tests async processing of http requests during step processing.
 */
class HttpAsyncRequestStepTest {

  public static final String MANAGED_SERVER1 = "ms1";
  private final HttpResponseStepImpl responseStep = new HttpResponseStepImpl(null);
  private final Packet packet = new Packet();
  private final List<Memento> mementos = new ArrayList<>();
  private final HttpResponse<String> response = createStub(HttpResponseStub.class, 200);
  private HttpRequestStep requestStep;
  private final CompletableFuture<HttpResponse<String>> responseFuture = new CompletableFuture<>();
  private final HttpRequestStep.FutureFactory futureFactory = r -> responseFuture;
  private final Collection<LogRecord> logRecords = new ArrayList<>();
  private TestUtils.ConsoleHandlerMemento consoleMemento;

  @BeforeEach
  public void setUp() throws NoSuchFieldException {
    mementos.add(consoleMemento = TestUtils.silenceOperatorLogger()
          .collectLogMessages(logRecords, HTTP_METHOD_FAILED, HTTP_REQUEST_TIMED_OUT)
          .withLogLevel(Level.FINE)
          .ignoringLoggedExceptions(HttpRequestStep.HttpTimeoutException.class));
    mementos.add(StaticStubSupport.install(HttpRequestStep.class, "factory", futureFactory));
    mementos.add(TuningParametersStub.install());

    requestStep = createStep();
  }

  @AfterEach
  public void tearDown() {
    mementos.forEach(Memento::revert);
  }

  @Test
  void classImplementsStep() {
    assertThat(HttpRequestStep.class, typeCompatibleWith(Step.class));
  }

  @Test
  void constructorReturnsInstanceLinkedToResponse() {
    assertThat(requestStep.getNext(), sameInstance(responseStep));
  }

  @Nonnull
  private HttpRequestStep createStep() {
    return HttpRequestStep.createGetRequest("http://localhost/nothing", responseStep);
  }

  private DomainPresenceInfo createDomainPresenceInfo(V1Pod msPod, int httpRequestFailureCount) {
    packet.put(ProcessingConstants.SERVER_NAME, MANAGED_SERVER1);
    DomainPresenceInfo info = new DomainPresenceInfo(NS, UID);
    info.setServerPod(MANAGED_SERVER1, msPod);
    info.setHttpRequestFailureCount(MANAGED_SERVER1, httpRequestFailureCount);
    return info;
  }

  private void collectHttpWarningMessage() {
    consoleMemento
        .collectLogMessages(logRecords, HTTP_REQUEST_GOT_THROWABLE)
        .withLogLevel(Level.WARNING);
  }

  @Test
  void whenThrowableResponseReceivedAndPodBeingDeletedByOperator_dontLogMessage() {
    collectHttpWarningMessage();
    DomainPresenceInfo info = createDomainPresenceInfo(new V1Pod().metadata(new V1ObjectMeta()), 0);
    info.setServerPodBeingDeleted(MANAGED_SERVER1, true);
    packet.put(ProcessingConstants.DOMAIN_PRESENCE_INFO, info);
    final Void nextAction = requestStep.apply(packet);

    completeWithThrowableBeforeTimeout(nextAction, new Throwable("Test"));

    assertThat(logRecords, not(containsWarning(HTTP_REQUEST_GOT_THROWABLE)));
  }

  @Test
  void whenThrowableResponseReceivedAndPodHasDeletionTimestamp_dontLogMessage() {
    collectHttpWarningMessage();
    packet.put(ProcessingConstants.DOMAIN_PRESENCE_INFO,
        createDomainPresenceInfo(new V1Pod().metadata(new V1ObjectMeta().deletionTimestamp(OffsetDateTime.now())), 0));
    final Void nextAction = requestStep.apply(packet);

    completeWithThrowableBeforeTimeout(nextAction, new Throwable("Test"));

    assertThat(logRecords, not(containsWarning(HTTP_REQUEST_GOT_THROWABLE)));
  }

  @Test
  void whenThrowableResponseReceivedServerNotShuttingDownAndFailureCountLowerThanThreshold_dontLogMessage() {
    collectHttpWarningMessage();
    packet.put(ProcessingConstants.DOMAIN_PRESENCE_INFO,
        createDomainPresenceInfo(new V1Pod().metadata(new V1ObjectMeta()), 0));
    final Void nextAction = requestStep.apply(packet);

    completeWithThrowableBeforeTimeout(nextAction, new Throwable("Test"));

    assertThat(logRecords, not(containsWarning(HTTP_REQUEST_GOT_THROWABLE)));
  }

  @Test
  void whenResponseReceived_populatePacket() {
    Void nextAction = requestStep.apply(packet);

    receiveResponseBeforeTimeout(nextAction, response);

    assertThat(getResponse(), sameInstance(response));
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> getResponse() {
    return (HttpResponse) packet.get(RESPONSE);
  }

}

