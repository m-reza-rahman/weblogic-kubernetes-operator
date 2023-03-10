// Copyright (c) 2017, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.steps;

import io.kubernetes.client.openapi.models.V1Pod;
import oracle.kubernetes.operator.PodAwaiterStepFactory;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.wlsconfig.WlsDomainConfig;
import oracle.kubernetes.operator.work.Component;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

public class WatchPodReadyAdminStep extends Step {

  private final PodAwaiterStepFactory podAwaiterStepFactory;

  public WatchPodReadyAdminStep(PodAwaiterStepFactory podAwaiterStepFactory, Step next) {
    super(next);
    this.podAwaiterStepFactory = podAwaiterStepFactory;
  }

  @Override
  public Void apply(Packet packet) {
    DomainPresenceInfo info = (DomainPresenceInfo) packet.get(ProcessingConstants.DOMAIN_PRESENCE_INFO);
    WlsDomainConfig domainTopology =
        (WlsDomainConfig) packet.get(ProcessingConstants.DOMAIN_TOPOLOGY);
    V1Pod adminPod = info.getServerPod(domainTopology.getAdminServerName());

    packet.put(ProcessingConstants.PODWATCHER_COMPONENT_NAME, podAwaiterStepFactory);
    return doNext(podAwaiterStepFactory.waitForReady(adminPod, getNext()), packet);
  }
}
