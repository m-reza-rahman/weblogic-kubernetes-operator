// Copyright (c) 2021, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import oracle.weblogic.kubernetes.actions.impl.NginxParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ExecCommand;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.KUBERNETES_CLI;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.OKD;
import static oracle.weblogic.kubernetes.TestConstants.OKE_CLUSTER_PRIVATEIP;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WLS;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteImage;
import static oracle.weblogic.kubernetes.actions.TestActions.getPod;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.uninstallNginx;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNextFreePort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getServiceExtIPAddrtOke;
import static oracle.weblogic.kubernetes.utils.ExecCommand.exec;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createImageAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.imageRepoLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.LoadBalancerUtils.createIngressForDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.LoadBalancerUtils.installAndVerifyNginx;
import static oracle.weblogic.kubernetes.utils.MonitoringUtils.createAndVerifyDomain;
import static oracle.weblogic.kubernetes.utils.MySQLDBUtils.createMySQLDB;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verify WebApp can be accessed via NGINX ingress controller if db is installed.
 */
@DisplayName("Verify WebApp can be accessed via NGINX ingress controller if db is installed")
@Tag("oke-gate")
@Tag("kind-parallel")
@Tag("okd-wls-mrg")
@IntegrationTest
class ItTest {

  // domain constants
  private static final int replicaCount = 2;
  private static int managedServersCount = 2;

  private static String domain2Namespace = null;

  private static String domain2Uid = "dbtest-domain-2";

  private static NginxParams nginxHelmParams = null;
  private static int nodeportshttp = 0;
  private static int nodeportshttps = 0;

  private static List<String> ingressHost2List = null;



  private static String ingressIP = null;

  private static final String TEST_WDT_FILE = "/sample-topology.yaml";
  private static final String TEST_IMAGE_NAME = "dbtest-image";
  private static final String SESSMIGR_APP_NAME = "sessmigr-app";

  private static String cluster1Name = "cluster-1";
  private static String cluster2Name = "cluster-2";
  private static String miiImage = null;
  private static String wdtImage = null;
  private static final String SESSMIGR_APP_WAR_NAME = "sessmigr-war";


  private static int managedServerPort = 8001;
  private static int nodeportPrometheus;

  private static Map<String, Integer> clusterNameMsPortMap;
  private static LoggingFacade logger = null;
  private static List<String> clusterNames = new ArrayList<>();

  private static String dbUrl;

  /**
   * Install operator and NGINX. Create model in image domain with multiple clusters.
   * Create ingress for the domain.
   *
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   *                   JUnit engine parameter resolution mechanism
   */
  @BeforeAll

  public static void initAll(@Namespaces(4) List<String> namespaces) {

    logger = getLogger();

    logger.info("Get a unique namespace for operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    final String opNamespace = namespaces.get(0);


    logger.info("Get a unique namespace for WebLogic domain2");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domain2Namespace = namespaces.get(1);


    logger.info("Get a unique namespace for NGINX");
    assertNotNull(namespaces.get(2), "Namespace list is null");
    final String nginxNamespace = namespaces.get(2);


    logger.info("install and verify operator");
    installAndVerifyOperator(opNamespace, domain2Namespace);

    if (!OKD) {
      // install and verify NGINX
      nginxHelmParams = installAndVerifyNginx(nginxNamespace, 0, 0);
      String nginxServiceName = nginxHelmParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
      ingressIP = getServiceExtIPAddrtOke(nginxServiceName, nginxNamespace) != null
          ? getServiceExtIPAddrtOke(nginxServiceName, nginxNamespace) : K8S_NODEPORT_HOST;
      logger.info("NGINX service name: {0}", nginxServiceName);
      nodeportshttp = getServiceNodePort(nginxNamespace, nginxServiceName, "http");
      nodeportshttps = getServiceNodePort(nginxNamespace, nginxServiceName, "https");
      logger.info("NGINX http node port: {0}", nodeportshttp);
      logger.info("NGINX https node port: {0}", nodeportshttps);
    }
    clusterNameMsPortMap = new HashMap<>();
    clusterNameMsPortMap.put(cluster1Name, managedServerPort);
    clusterNameMsPortMap.put(cluster2Name, managedServerPort);
    clusterNames.add(cluster1Name);
    clusterNames.add(cluster2Name);


    //start  MySQL database instance
    if (!WEBLOGIC_IMAGE_TAG.equals("12.2.1.4")) {
      assertDoesNotThrow(() -> {
        String dbService = createMySQLDB("mysql", "root", "root123", domain2Namespace, null);
        assertNotNull(dbService, "Failed to create database");
        V1Pod pod = getPod(domain2Namespace, null, "mysql");
        createFileInPod(pod.getMetadata().getName(), domain2Namespace, "root123");
        runMysqlInsidePod(pod.getMetadata().getName(), domain2Namespace, "root123", "/tmp/grant.sql");
        runMysqlInsidePod(pod.getMetadata().getName(), domain2Namespace, "root123", "/tmp/create.sql");
        dbUrl = "jdbc:mysql://" + dbService + "." + domain2Namespace + ".svc:3306";
      });
    }
  }

  /**
   * Test that if db is not started, access to app fails in Weblogic versions above 12.2.1.4.
   * Create domain in Image with app.
   * Verify access to app via nginx..
   */
  @Test
  @DisplayName("Test Test that if db is not started, access to app fails.")
  void testAccesToWebApp() throws Exception {

    wdtImage = createAndVerifyDomainInImage();
    logger.info("Create wdt domain and verify that it's running");
    createAndVerifyDomain(wdtImage, domain2Uid, domain2Namespace, "Image", replicaCount,
        false, null, null);

    if (!OKD) {
      ingressHost2List
          = createIngressForDomainAndVerify(domain2Uid, domain2Namespace, 0, clusterNameMsPortMap,
          true, nginxHelmParams.getIngressClassName(), false, 0);
      logger.info("verify access to Monitoring Exporter");
      if (OKE_CLUSTER_PRIVATEIP) {
        verifyMyAppAccessThroughNginx(ingressHost2List.get(0), managedServersCount, ingressIP);
      } else {
        verifyMyAppAccessThroughNginx(ingressHost2List.get(0), managedServersCount,
            K8S_NODEPORT_HOST + ":" + nodeportshttp);
      }
    }
  }

  public static void verifyMyAppAccessThroughNginx(String nginxHost, int replicaCount, String hostPort) {

    List<String> managedServerNames = new ArrayList<>();
    for (int i = 1; i <= replicaCount; i++) {
      managedServerNames.add(MANAGED_SERVER_NAME_BASE + i);
    }

    // check that NGINX can access the sample apps from all managed servers in the domain
    String curlCmd =
        String.format("curl -g --silent --show-error --noproxy '*' -H 'host: %s' http://%s:%s@%s/"
                + SESSMIGR_APP_WAR_NAME + "/?getCounter",
            nginxHost,
            ADMIN_USERNAME_DEFAULT,
            ADMIN_PASSWORD_DEFAULT,
            hostPort);
    ExecResult result = assertDoesNotThrow(() -> ExecCommand.exec(curlCmd, true));

    String response = result.stdout().trim();
    logger.info("Response : exitValue {0}, stdout {1}, stderr {2}", result.exitValue(), response, result.stderr());
  }


  private static void createFileInPod(String podName, String namespace, String password) throws IOException {
    final LoggingFacade logger = getLogger();

    ExecResult result = assertDoesNotThrow(() -> exec(new String("hostname -i"), true));
    String ip = result.stdout();

    Path sourceFile = Files.writeString(Paths.get(WORK_DIR, "grant.sql"),
        "select user();\n"
            + "SELECT host, user FROM mysql.user;\n"
            + "CREATE USER 'root'@'%' IDENTIFIED BY '" + password + "';\n"
            + "GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;\n"
            + "CREATE USER 'root'@'" + ip + "' IDENTIFIED BY '" + password + "';\n"
            + "GRANT ALL PRIVILEGES ON *.* TO 'root'@'" + ip + "' WITH GRANT OPTION;\n"
            + "SELECT host, user FROM mysql.user;");
    Path source1File = Files.writeString(Paths.get(WORK_DIR, "create.sql"),
        "CREATE DATABASE " + domain2Uid + ";\n"
            + "CREATE USER 'wluser1' IDENTIFIED BY 'wlpwd123';\n"
            + "GRANT ALL ON " + domain2Uid + ".* TO 'wluser1';");
    StringBuffer mysqlCmd1 = new StringBuffer("cat " + source1File.toString() + " | ");
    mysqlCmd1.append(KUBERNETES_CLI + " exec -i -n ");
    mysqlCmd1.append(namespace);
    mysqlCmd1.append(" ");
    mysqlCmd1.append(podName);
    mysqlCmd1.append(" -- /bin/bash -c \"");
    mysqlCmd1.append("cat > /tmp/create.sql\"");
    StringBuffer mysqlCmd = new StringBuffer("cat " + sourceFile.toString() + " | ");
    mysqlCmd.append(KUBERNETES_CLI + " exec -i -n ");
    mysqlCmd.append(namespace);
    mysqlCmd.append(" ");
    mysqlCmd.append(podName);
    mysqlCmd.append(" -- /bin/bash -c \"");
    mysqlCmd.append("cat > /tmp/grant.sql\"");
    logger.info("mysql command {0}", mysqlCmd.toString());
    result = assertDoesNotThrow(() -> exec(new String(mysqlCmd), false));
    logger.info("mysql returned {0}", result.toString());
    logger.info("mysql returned EXIT value {0}", result.exitValue());
    assertEquals(0, result.exitValue(), "mysql execution fails");
    logger.info("mysql command {0}", mysqlCmd1.toString());
    result = assertDoesNotThrow(() -> exec(new String(mysqlCmd1), false));
    logger.info("mysql returned {0}", result.toString());
    logger.info("mysql returned EXIT value {0}", result.exitValue());
    assertEquals(0, result.exitValue(), "mysql execution fails");

  }

  @AfterAll
  public void tearDownAll() {

    // uninstall NGINX release
    logger.info("Uninstalling NGINX");
    if (nginxHelmParams != null) {
      assertThat(uninstallNginx(nginxHelmParams.getHelmParams()))
          .as("Test uninstallNginx1 returns true")
          .withFailMessage("uninstallNginx() did not return true")
          .isTrue();
    }

    // delete mii domain images created for parameterized test
    if (wdtImage != null) {
      deleteImage(miiImage);
    }
  }

  /**
   * Create and verify domain in image from endtoend sample topology with webapp.
   * @return image name
   */
  private static String createAndVerifyDomainInImage() {
    // create image with model files
    logger.info("Create image with model file with  app and verify");



    List<String> appList = new ArrayList<>();
    appList.add(SESSMIGR_APP_NAME);


    int t3ChannelPort = getNextFreePort();

    Properties p = new Properties();
    p.setProperty("ADMIN_USER", ADMIN_USERNAME_DEFAULT);
    p.setProperty("ADMIN_PWD", ADMIN_PASSWORD_DEFAULT);
    p.setProperty("DOMAIN_NAME", domain2Uid);
    p.setProperty("DBURL", dbUrl);
    p.setProperty("ADMIN_NAME", "admin-server");
    p.setProperty("PRODUCTION_MODE_ENABLED", "true");
    p.setProperty("CLUSTER_NAME", cluster1Name);
    p.setProperty("CLUSTER_TYPE", "DYNAMIC");
    p.setProperty("CONFIGURED_MANAGED_SERVER_COUNT", "2");
    p.setProperty("MANAGED_SERVER_NAME_BASE", "managed-server");
    p.setProperty("T3_CHANNEL_PORT", Integer.toString(t3ChannelPort));
    p.setProperty("T3_PUBLIC_ADDRESS", K8S_NODEPORT_HOST);
    p.setProperty("MANAGED_SERVER_PORT", "8001");
    p.setProperty("SERVER_START_MODE", "prod");
    p.setProperty("ADMIN_PORT", "7001");
    p.setProperty("MYSQL_USER", "wluser1");
    p.setProperty("MYSQL_PWD", "wlpwd123");
    // create a temporary WebLogic domain property file as an input for WDT model file
    File domainPropertiesFile = assertDoesNotThrow(() ->
            File.createTempFile("domain", "properties"),
        "Failed to create domain properties file");
    assertDoesNotThrow(() ->
            p.store(new FileOutputStream(domainPropertiesFile), "WDT properties file"),
        "Failed to write domain properties file");

    final List<String> propertyList = Collections.singletonList(domainPropertiesFile.getPath());

    // build the model file list
    final List<String> modelList = Collections.singletonList(MODEL_DIR
        + TEST_WDT_FILE);

    wdtImage =
        createImageAndVerify(TEST_IMAGE_NAME,
            modelList,
            appList,
            propertyList,
            WEBLOGIC_IMAGE_NAME,
            WEBLOGIC_IMAGE_TAG,
            WLS,
            false,
            domain2Uid, false);

    // repo login and push image to registry if necessary
    imageRepoLoginAndPushImageToRegistry(wdtImage);

    return wdtImage;
  }

  private static void runMysqlInsidePod(String podName, String namespace, String password, String sqlFilePath) {
    final LoggingFacade logger = getLogger();

    logger.info("Sleeping for 1 minute before connecting to mysql db");
    assertDoesNotThrow(() -> TimeUnit.MINUTES.sleep(1));
    StringBuffer mysqlCmd = new StringBuffer(KUBERNETES_CLI + " exec -i -n ");
    mysqlCmd.append(namespace);
    mysqlCmd.append(" ");
    mysqlCmd.append(podName);
    mysqlCmd.append(" -- /bin/bash -c \"");
    mysqlCmd.append("mysql --force ");
    mysqlCmd.append("-u root -p" + password);
    mysqlCmd.append(" < ");
    mysqlCmd.append(sqlFilePath);
    mysqlCmd.append(" \"");
    logger.info("mysql command {0}", mysqlCmd.toString());
    ExecResult result = assertDoesNotThrow(() -> exec(new String(mysqlCmd), true));
    logger.info("mysql returned {0}", result.toString());
    logger.info("mysql returned EXIT value {0}", result.exitValue());
    assertEquals(0, result.exitValue(), "mysql execution fails");
  }
}
