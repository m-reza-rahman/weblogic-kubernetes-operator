// Copyright (c) 2021, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.actions.impl;

import oracle.weblogic.kubernetes.actions.impl.primitive.Installer;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ExecResult;

import static oracle.weblogic.kubernetes.actions.ActionConstants.REMOTECONSOLE_FILE;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Installer.defaultInstallRemoteConsoleParams;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.callWebAppAndWaitTillReady;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.ExecCommand.exec;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Utility class for WebLogic Remote Console.
 */
public class WebLogicRemoteConsole {

  private static final LoggingFacade logger = getLogger();

  /**
   * Install WebLogic Remote Console.
   * @param domainNamespace namespace in which the domain will be created
   * @param adminServerPodName the name of the admin server pod
   * @return true if WebLogic Remote Console is successfully installed, false otherwise.
   */
  public static boolean installWlsRemoteConsole(String domainNamespace, String adminServerPodName) {
    if (!downloadRemoteConsole()) {
      return false;
    }

    return runRemoteConsole(domainNamespace, adminServerPodName);
  }

  /**
   * Shutdown WebLogic Remote Console.
   *
   * @return true if WebLogic Remote Console is successfully shutdown, false otherwise.
   */
  public static boolean shutdownWlsRemoteConsole() {

    String command = "kill -9 `jps | grep console.jar | awk '{print $1}'`";
    logger.info("Command to shutdown the remote console: {0}", command);
    ExecResult result = assertDoesNotThrow(() -> exec(command, true));
    logger.info("Shutdown command returned {0}", result.toString());
    logger.info(" Shutdown command returned EXIT value {0}", result.exitValue());

    return (result.exitValue() == 0);

  }

  private static boolean downloadRemoteConsole() {

    return Installer.withParams(
        defaultInstallRemoteConsoleParams())
        .download();
  }

  private static boolean runRemoteConsole(String domainNamespace, String adminServerPodName) {

    StringBuilder javaCmd = new StringBuilder("java");
    javaCmd.append(" -jar ");
    javaCmd.append(REMOTECONSOLE_FILE);
    javaCmd.append(" > ");
    javaCmd.append(WORK_DIR).append("/backend");
    javaCmd.append("/remoteconsole.out 2>&1 ");
    javaCmd.append(WORK_DIR).append("/backend");
    javaCmd.append(" &");
    logger.info("java command to start remote console {0}", javaCmd.toString());

    ExecResult result = assertDoesNotThrow(() -> exec(new String(javaCmd), true));
    logger.info("java returned {0}", result.toString());
    logger.info("java returned EXIT value {0}", result.exitValue());

    return ((result.exitValue() == 0) && accessRemoteConsole());

  }

  private static boolean accessRemoteConsole() {

    String curlCmd = "curl -s -L --show-error --noproxy '*' "
        + " http://localhost:8012"
        + " --write-out %{http_code} -o /dev/null";
    logger.info("Executing curl command {0}", curlCmd);

    testUntil((() -> callWebAppAndWaitTillReady(curlCmd, 1)), logger,
        "Waiting for remote console access to return 200 status code");

    return true;
  }

}
