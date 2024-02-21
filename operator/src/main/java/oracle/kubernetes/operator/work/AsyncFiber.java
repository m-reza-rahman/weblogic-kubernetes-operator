// Copyright (c) 2020, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.work;

/**
 * Defines operations on a fiber that may be done by asynchronous processing.
 */
public interface AsyncFiber {

  /**
   * Creates a child Fiber. If this Fiber is cancelled, so will all the children.
   * @param completionCallback The callback to be invoked when the processing is finished and the
   *     final packet is available.
   * @return a new child fiber
   */
  Fiber createChildFiber(Fiber.CompletionCallback completionCallback);
}
