// Copyright (c) 2020, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.assertions.impl;

import oracle.weblogic.kubernetes.actions.impl.primitive.Command;
import oracle.weblogic.kubernetes.actions.impl.primitive.CommandParams;

import static oracle.weblogic.kubernetes.TestConstants.KUBERNETES_CLI;

/**
 * Assertions for applications that are deployed in a domain custom resource.
 *
 */

public class Application {

  /**
   * Check if an application is accessible inside a WebLogic server pod using "KUBERNETES_CLI exec" command.
   *
   * @param namespace Kubernetes namespace where the WebLogic server pod is running
   * @param podName name of the WebLogic server pod
   * @param port internal port of the managed server running in the pod
   * @param appPath path to access the application
   * @param expectedResponse expected response from the app
   * @return true if the command succeeds
   */
  public static boolean appAccessibleInPodKubernetesCLI(
      String namespace,
      String podName,
      String port,
      String appPath,
      String expectedResponse
  ) {

    // calling "KUBERNETES_CLI exec" command to access the app inside a pod
    String cmd = String.format(
        KUBERNETES_CLI + " -n %s exec -it %s -- /bin/bash -c 'curl http://%s:%s/%s'",
        namespace,
        podName,
        podName,
        port,
        appPath);

    CommandParams params = Command
        .defaultCommandParams()
        .command(cmd)
        .saveResults(true)
        .redirect(false)
        .verbose(false);
    return Command.withParams(params).executeAndVerify(expectedResponse);
  }

}
