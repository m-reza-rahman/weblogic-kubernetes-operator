// Copyright (c) 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.webhooks.resource;

import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.webhooks.model.AdmissionResponse;
import oracle.kubernetes.weblogic.domain.model.ClusterResource;
import org.jetbrains.annotations.NotNull;

/**
 * ClusterCreateAdmissionChecker provides the validation functionality for the validating webhook. It takes a
 * proposed new cluster resource and returns a result to indicate if the proposed resource is allowed, and if not,
 * what the problem is.
 *
 * Currently, it always accepts a new cluster resource.
 * </p>
 */

public class ClusterCreateAdmissionChecker extends AdmissionChecker {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Webhook", "Operator");

  private final ClusterResource proposedCluster;
  private final AdmissionResponse response = new AdmissionResponse();

  /** Construct a ClusterCreateAdmissionChecker. */
  public ClusterCreateAdmissionChecker(@NotNull ClusterResource proposedCluster) {
    this.proposedCluster = proposedCluster;
  }

  @Override
  AdmissionResponse validate() {
    LOGGER.fine("Validating new ClusterResource " + proposedCluster);
    response.allowed(isProposedChangeAllowed());
    return response;
  }

  @Override
  public boolean isProposedChangeAllowed() {
    return true;
  }
}
