// Copyright (c) 2021, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import oracle.weblogic.kubernetes.actions.impl.primitive.Command;
import oracle.weblogic.kubernetes.actions.impl.primitive.CommandParams;

import static oracle.weblogic.kubernetes.TestConstants.OKD;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OKDUtils {
  /**
   * We need to expose the service as a route for ingress.
   *
   * @param serviceName - Name of the route and service
   * @param namespace - Namespace where the route is exposed
   */
  public static String createRouteForOKD(String serviceName, String namespace, String... routeName) {
    boolean routeExists;
    String command = "oc -n " + namespace + " expose service " + serviceName;
    if (OKD) {
      getLogger().info("Going to create route for OKD");
      if (routeName.length == 0) {
        routeExists = doesRouteExist(serviceName, namespace);
      } else {
        routeExists = doesRouteExist(routeName[0], namespace);
        command = command + " --name " + routeName[0];
        serviceName = routeName[0];
      }
      if (!routeExists) {
        assertTrue(Command
            .withParams(new CommandParams()
              .command(command))
            .execute(), "oc expose service failed");
      }
      return getRouteHost(namespace, serviceName);
    } else {
      getLogger().info("This is non OKD env. No route is needed");
      return null;
    }
  }

  /**
   * Get the host name of the route.
   *
   * @param namespace - Namespace where the route is exposed
   * @param serviceName - Name of the route
   */
  public static String getRouteHost(String namespace, String serviceName) {
    if (OKD) {
      String command = "oc -n " + namespace + " get routes " + serviceName + "  '-o=jsonpath={.spec.host}'";

      ExecResult result = Command.withParams(
          new CommandParams()
              .command(command))
          .executeAndReturnResult();

      boolean success =
          result != null
              && result.exitValue() == 0
              && result.stdout() != null
              && result.stdout().contains(serviceName);

      String outStr = "Did not get the route hostName \n";
      outStr += ", command=\n{\n" + command + "\n}\n";
      outStr += ", stderr=\n{\n" + (result != null ? result.stderr() : "") + "\n}\n";
      outStr += ", stdout=\n{\n" + (result != null ? result.stdout() : "") + "\n}\n";

      assertTrue(success, outStr);

      getLogger().info("exitValue = {0}", result.exitValue());
      getLogger().info("stdout = {0}", result.stdout());
      getLogger().info("stderr = {0}", result.stderr());

      String hostName = result.stdout();
      getLogger().info("route hostname = {0}", hostName);
  
      return hostName;
    } else {
      return null;
    }
  }

  /**
   * Sets TLS termination in the route to passthrough.
   *
   * @param routeName name of the route
   * @param namespace namespace where the route is created
   */
  public static void setTlsTerminationForRoute(String routeName, String namespace) {
    if (OKD) {
      String command = "oc -n " + namespace + " patch route " + routeName
                          +  " --patch '{\"spec\": {\"tls\": {\"termination\": \"passthrough\"}}}'";

      ExecResult result = Command.withParams(
          new CommandParams()
              .command(command))
          .executeAndReturnResult();

      boolean success =
          result != null
              && result.exitValue() == 0
              && result.stdout() != null
              && result.stdout().contains("patched");

      String outStr = "Setting tls termination in route failed \n";
      outStr += ", command=\n{\n" + command + "\n}\n";
      outStr += ", stderr=\n{\n" + (result != null ? result.stderr() : "") + "\n}\n";
      outStr += ", stdout=\n{\n" + (result != null ? result.stdout() : "") + "\n}\n";

      assertTrue(success, outStr);

      getLogger().info("exitValue = {0}", result.exitValue());
      getLogger().info("stdout = {0}", result.stdout());
      getLogger().info("stderr = {0}", result.stderr());
    }
  }

  /**
   * Sets TLS termination in the route to passthrough.
   *
   * @param routeName name of the route
   * @param namespace namespace where the route is created
   */
  public static void setTlsEdgeTerminationForRoute(String routeName, String namespace, 
                                                   Path keyFile, Path certFile) throws IOException {
    if (OKD) {
      String tlsKey = Files.readString(keyFile);
      // Remove the last \n from the String above
      tlsKey = tlsKey.replaceAll("[\n\r]$", "");
      String tlsCert = Files.readString(certFile);
      // Remove the last \n from the String above
      tlsCert = tlsCert.replaceAll("[\n\r]$", "");
      String command = "oc -n " + namespace + " patch route " + routeName
          + " --patch '{\"spec\": {\"tls\": {\"termination\": \"edge\"," 
                                          + "\"key\": \"" + tlsKey + "\","  
                                          + "\"certificate\": \"" + tlsCert + "\"}}}'";

      ExecResult result = Command.withParams(
          new CommandParams()
              .command(command))
          .executeAndReturnResult();

      boolean success =
          result != null
              && result.exitValue() == 0
              && result.stdout() != null
              && result.stdout().contains("patched");

      String outStr = "Setting tls termination in route failed \n";
      outStr += ", command=\n{\n" + command + "\n}\n";
      outStr += ", stderr=\n{\n" + (result != null ? result.stderr() : "") + "\n}\n";
      outStr += ", stdout=\n{\n" + (result != null ? result.stdout() : "") + "\n}\n";

      assertTrue(success, outStr);

      getLogger().info("exitValue = {0}", result.exitValue());
      getLogger().info("stdout = {0}", result.stdout());
      getLogger().info("stderr = {0}", result.stderr());
    }
  }

  /** 
   * Sets the target port of the route.
   * 
   * @param routeName  name of the route
   * @param namespace namespace where the route is created
   * @param port target port
   */
  public static void setTargetPortForRoute(String routeName, String namespace, int port) {
    if (OKD) {
      String command = "oc -n " + namespace + " patch route " + routeName
                          +  " --patch '{\"spec\": {\"port\": {\"targetPort\": \"" + port + "\"}}}'";

      ExecResult result = Command.withParams(
          new CommandParams()
              .command(command))
          .executeAndReturnResult();

      boolean success =
          result != null
              && result.exitValue() == 0
              && result.stdout() != null
              && result.stdout().contains("patched");

      String outStr = "Setting target port in route failed \n";
      outStr += ", command=\n{\n" + command + "\n}\n";
      outStr += ", stderr=\n{\n" + (result != null ? result.stderr() : "") + "\n}\n";
      outStr += ", stdout=\n{\n" + (result != null ? result.stdout() : "") + "\n}\n";

      assertTrue(success, outStr);

      getLogger().info("exitValue = {0}", result.exitValue());
      getLogger().info("stdout = {0}", result.stdout());
      getLogger().info("stderr = {0}", result.stderr());
    }
  }

  private static boolean doesRouteExist(String routeName, String namespace) {
    String command = "oc -n " + namespace + " get route " + routeName;

    ExecResult result = Command.withParams(
          new CommandParams()
              .command(command))
          .executeAndReturnResult();

    if (result != null) {
      getLogger().info("exitValue = {0}", result.exitValue());
      getLogger().info("stdout = {0}", result.stdout());
      getLogger().info("stderr = {0}", result.stderr());
    }

    return result != null
         && result.exitValue() == 0 
         && result.stdout() != null 
         && result.stdout().contains(routeName);
  }

  /**
   * add security context constraints to the service account of namespace.
   * @param serviceAccount - service account to add to scc
   * @param namespace - namespace to which the service account belongs
   */
  public static void addSccToNsSvcAccount(String serviceAccount, String namespace) {
    assertTrue(Command
        .withParams(new CommandParams()
            .command("oc adm policy add-scc-to-user privileged -z " + serviceAccount + " -n " + namespace))
        .execute(), "oc expose service failed");
  }
}
