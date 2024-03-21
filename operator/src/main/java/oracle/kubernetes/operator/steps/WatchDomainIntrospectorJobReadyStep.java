// Copyright (c) 2017, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.steps;

import java.time.Duration;
import javax.annotation.Nonnull;

import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.openapi.models.V1Job;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.tuning.TuningParameters;
import oracle.kubernetes.operator.watcher.JobWatcher;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

import static oracle.kubernetes.operator.DomainStatusUpdater.createRemoveFailuresStep;

public class WatchDomainIntrospectorJobReadyStep extends Step {

  @Override
  public @Nonnull Result apply(Packet packet) {
    V1Job domainIntrospectorJob = (V1Job) packet.get(ProcessingConstants.DOMAIN_INTROSPECTOR_JOB);

    if (hasNotCompleted(domainIntrospectorJob)) {
      return new Result(true,
              Duration.ofSeconds(TuningParameters.getInstance().getWatchTuning().getWatchBackstopRecheckDelay()));
    } else {
      return doNext(createRemoveFailuresStep(getNext()), packet);
    }
  }

  private boolean hasNotCompleted(V1Job domainIntrospectorJob) {
    return domainIntrospectorJob != null && !JobWatcher.isComplete(domainIntrospectorJob);
  }
}
