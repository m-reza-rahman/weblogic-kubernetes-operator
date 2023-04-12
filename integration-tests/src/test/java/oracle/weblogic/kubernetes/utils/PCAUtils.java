// Copyright (c) 2021, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1LoadBalancerIngress;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import oracle.weblogic.kubernetes.logging.LoggingFacade;

import static oracle.weblogic.kubernetes.assertions.impl.Kubernetes.getService;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PCAUtils {  
  
  /**
   * Retreive external IP from LoadBalancer.
   *
   * @param namespace namespace
   * @param lbName lb name
   * @return ip
   * @throws java.lang.Exception exception
   */
  public static String getLoadBalancerIP(String namespace, String lbName) throws Exception {
    LoggingFacade logger = getLogger();
    Map<String, String> labels = new HashMap<>();
    labels.put("app.kubernetes.io/name", "traefik");
    V1Service service = getService(lbName, labels, namespace);
    assertNotNull(service, "Can't find service with name " + lbName);
    logger.info("Found service with name {0} in {1} namespace ", lbName, namespace);
    List<V1LoadBalancerIngress> ingress = service.getStatus().getLoadBalancer().getIngress();
    if (ingress != null) {
      logger.info("LoadBalancer Ingress " + ingress.toString());
      V1LoadBalancerIngress lbIng = ingress.stream().filter(c
          -> !c.getIp().equals("pending")
      ).findAny().orElse(null);
      if (lbIng != null) {
        logger.info("LoadBalancer is created with external ip" + lbIng.getIp());
        return lbIng.getIp();
      }
    }
    return null;
  }
  
  /**
   * Retreive LoadBalancer port from service.
   *
   * @param namespace namespace
   * @param lbName lb name
   * @param portName web or webSecure
   * @return port
   * @throws java.lang.Exception exception
   */
  public static int getLoadBalancerPort(String namespace, String lbName, String portName) throws Exception {
    LoggingFacade logger = getLogger();
    Map<String, String> labels = new HashMap<>();
    labels.put("app.kubernetes.io/name", "traefik");
    V1Service service = getService(lbName, labels, namespace);
    assertNotNull(service, "Can't find service with name " + lbName);
    logger.info("Found service with name {0} in {1} namespace ", lbName, namespace);
    List<V1ServicePort> ports = service.getSpec().getPorts();
    if (ports != null) {
      logger.info("LoadBalancer Ingress " + ports.toString());
      V1ServicePort webport = ports.stream().filter(c -> !c.getName().equals(portName)).findAny().orElse(null);
      if (webport != null) {
        logger.info("LoadBalancer is web port" + webport.getPort());
        return webport.getPort();
      }
    }
    return -1;
  }
}
