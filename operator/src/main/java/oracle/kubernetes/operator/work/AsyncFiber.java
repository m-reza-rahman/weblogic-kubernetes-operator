// Copyright (c) 2020, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

import java.util.concurrent.TimeUnit;

/**
 * Defines operations on a fiber that may be done by asynchronous processing.
 */
public interface AsyncFiber {

  /**
   * Schedules an operation for some time in the future.
   *
   * @param timeout the interval before the check should run, in units
   * @param unit the unit of time that defines the interval
   * @param runnable the operation to run
   */
  void scheduleOnce(long timeout, TimeUnit unit, Runnable runnable);

  /**
   * Creates a child Fiber. If this Fiber is cancelled, so will all of the children.
   *
   * @return a new child fiber
   */
  Fiber createChildFiber();
}
