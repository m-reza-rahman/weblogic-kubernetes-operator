// Copyright (c) 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.util.function.Predicate;

import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.work.Step;

/**
 * An interface that defines support required by a MakeRightDomainOperation being run.
 */
public interface MakeRightExecutor {

  /**
   * Runs the specified make-right if the shouldProceed callback returns true.
   * @param operation a defined make-right operation
   * @param shouldProceed a predicate run against the cached presence info to decide if the operation should be run
   */
  void runMakeRight(MakeRightDomainOperation operation, Predicate<DomainPresenceInfo> shouldProceed);

  /**
   * Creates steps to process namespaced Kubernetes resources.
   * @param processors the processing to be done
   * @param info the presence info which encapsulates the domain
   */
  Step createNamespacedResourceSteps(Processors processors, DomainPresenceInfo info);

  /**
   * Starts periodic updates of the domain status.
   * @param info the presence info which encapsulates the domain
   */
  void scheduleDomainStatusUpdates(DomainPresenceInfo info);

  /**
   * Ends ongoing period updates of the domain status.
   * @param info the presence info which encapsulates the domain
   */
  void endScheduledDomainStatusUpdates(DomainPresenceInfo info);

  /**
   * Adds the specified presence info to a cache.
   * @param info the presence info which encapsulates the domain
   */
  void registerDomainPresenceInfo(DomainPresenceInfo info);

  /**
   * Removes the specified presence info from the cache.
   * @param info the presence info which encapsulates the domain
   */
  void unregisterDomainPresenceInfo(DomainPresenceInfo info);
}
