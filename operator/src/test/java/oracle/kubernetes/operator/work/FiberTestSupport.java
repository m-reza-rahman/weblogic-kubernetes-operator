// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import oracle.kubernetes.operator.MainDelegate;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.logging.LoggingContext;

import static com.meterware.simplestub.Stub.createStrictStub;
import static oracle.kubernetes.operator.logging.LoggingContext.LOGGING_CONTEXT_KEY;

/**
 * Support for writing unit tests that use a fiber to run steps. Such tests can call #runStep to
 * initiate fiber execution, which will happen in simulated time. That time starts at zero when an
 * instance is created, and can be increased by a call to setTime. This is useful to run steps which
 * are scheduled for the future, without using delays. As all steps are run in a single thread,
 * there is no need to add semaphores to coordinate them.
 *
 * <p>The components in the packet used by the embedded fiber may be access via
 * #getPacketComponents.
 */
@SuppressWarnings("UnusedReturnValue")
public class FiberTestSupport {
  private final CompletionCallbackStub completionCallback = new CompletionCallbackStub();
  private final ExecutorServiceStub executor = ExecutorServiceStub.create();
  private final Engine engine = new Engine(executor);

  private Packet packet = new Packet();

  public FiberTestSupport() {
    MainDelegateStub mainDelegate = createStrictStub(MainDelegateStub.class);
    packet.put(ProcessingConstants.DELEGATE_COMPONENT_NAME, mainDelegate);
  }

  public ExecutorService getExecutorService() {
    return executor;
  }

  /** Creates a single-threaded FiberGate instance. */
  public FiberGate createFiberGate() {
    return new FiberGate(engine);
  }

  /**
   * Returns the number of items actually run since this object was created.
   */
  public int getNumItemsRun() {
    return executor.getNumItemsRun();
  }

  /**
   * Returns the engine used by this support object.
   *
   * @return the current engine object
   */
  public Engine getEngine() {
    return engine;
  }

  public Packet getPacket() {
    return packet;
  }

  public FiberTestSupport withClearPacket() {
    packet.clear();
    return this;
  }

  public FiberTestSupport addToPacket(String key, Object value) {
    packet.put(key, value);
    return this;
  }

  public FiberTestSupport addDomainPresenceInfo(DomainPresenceInfo info) {
    packet.put(ProcessingConstants.DOMAIN_PRESENCE_INFO, info);
    return this;
  }

  public FiberTestSupport addLoggingContext(LoggingContext loggingContext) {
    packet.put(LOGGING_CONTEXT_KEY, loggingContext);
    return this;
  }

  /**
   * Specifies a predefined packet to use for the next run.
   * @param packet the new packet
   */
  public FiberTestSupport withPacket(@Nonnull Packet packet) {
    this.packet = packet;
    return this;
  }

  public FiberTestSupport withCompletionAction(Runnable completionAction) {
    completionCallback.setCompletionAction(completionAction);
    return this;
  }

  /**
   * Starts a unit-test fiber with the specified steps.
   *
   * @param step the first step to run
   */
  public Packet runSteps(Step... step) {
    final Step stepList = (step.length == 1 ? step[0] : Step.chain(step));
    return runSteps(packet, stepList);
  }

  /**
   * Starts a unit-test fiber with the specified packet and step.
   *
   * @param packet the packet to use
   * @param step the first step to run
   */
  public Packet runSteps(Packet packet, Step step) {
    Fiber fiber = engine.createFiber();
    fiber.start(step, packet, completionCallback);

    return packet;
  }

  /**
   * Starts a unit-test fiber with the specified step.
   *
   * @param nextStep the first step to run
   */
  public Packet runSteps(StepFactory factory, Step nextStep) {
    return runSteps(factory.createStepList(nextStep));
  }

  /**
   * Verifies that the completion callback's 'onThrowable' method was invoked with a throwable of
   * the specified class. Clears the throwable so that #throwOnFailure will not throw the expected
   * exception.
   *
   * @param throwableClass the class of the excepted throwable
   */
  public void verifyCompletionThrowable(Class<? extends Throwable> throwableClass) {
    completionCallback.verifyThrowable(throwableClass);
  }

  /**
   * If the completion callback's 'onThrowable' method was invoked, throws the specified throwable.
   * Note that a call to #verifyCompletionThrowable will consume the throwable, so this method will
   * not throw it.
   *
   * @throws Exception the exception reported as a failure
   */
  public void throwOnCompletionFailure() throws Exception {
    completionCallback.throwOnFailure();
  }

  @FunctionalInterface
  public interface StepFactory {
    Step createStepList(Step next);
  }

  abstract static class ExecutorServiceStub implements ExecutorService {

    private final Queue<Runnable> queue = new ArrayDeque<>();
    private Runnable current;
    private int numItemsRun;

    static ExecutorServiceStub create() {
      return createStrictStub(ExecutorServiceStub.class);
    }

    int getNumItemsRun() {
      return numItemsRun;
    }

    @Override
    public void execute(@Nullable Runnable command) {
      queue.add(command);
      if (current == null) {
        runNextRunnable();
      }
    }

    private void runNextRunnable() {
      while (null != (current = queue.poll())) {
        current.run();
        numItemsRun++;
        current = null;
      }
    }

  }

  static class CompletionCallbackStub implements Fiber.CompletionCallback {
    private Throwable throwable;
    private Runnable completionAction;

    void setCompletionAction(Runnable completionAction) {
      this.completionAction = completionAction;
    }

    @Override
    public void onCompletion(Packet packet) {
      Optional.ofNullable(completionAction).ifPresent(Runnable::run);
    }

    @Override
    public void onThrowable(Packet packet, Throwable throwable) {
      this.throwable = throwable;
    }

    /**
     * Verifies that 'onThrowable' was invoked with a throwable of the specified class. Clears the
     * throwable so that #throwOnFailure will not throw the expected exception.
     *
     * @param throwableClass the class of the excepted throwable
     */
    void verifyThrowable(Class<?> throwableClass) {
      Throwable actual = throwable;
      throwable = null;

      if (actual == null) {
        throw new AssertionError("Expected exception: " + throwableClass.getName());
      }
      if (!throwableClass.isInstance(actual)) {
        throw new AssertionError(
            "Expected exception: " + throwableClass.getName() + " but was " + actual);
      }
    }

    /**
     * If 'onThrowable' was invoked, throws the specified throwable. Note that a call to
     * #verifyThrowable will consume the throwable, so this method will not throw it.
     *
     * @throws Exception the exception reported as a failure
     */
    void throwOnFailure() throws Exception {
      if (throwable == null) {
        return;
      }
      if (throwable instanceof Error) {
        throw (Error) throwable;
      }
      throw (Exception) throwable;
    }
  }

  abstract static class MainDelegateStub implements MainDelegate {
    public File getDeploymentHome() {
      return new File("/deployment");
    }
  }
}
