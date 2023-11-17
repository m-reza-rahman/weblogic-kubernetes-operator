// Copyright (c) 2020, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.DomainResource;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.OnlineUpdate;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.AppParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.WitParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.DomainUtils;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.ENCRYPION_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ENCRYPION_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.IMAGE_PULL_POLICY;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_DEPLOYMENT_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.OKE_CLUSTER;
import static oracle.weblogic.kubernetes.TestConstants.SSL_PROPERTIES;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_SLIM;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ARCHIVE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.buildAppArchive;
import static oracle.weblogic.kubernetes.actions.TestActions.createDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.defaultAppParams;
import static oracle.weblogic.kubernetes.actions.TestActions.getPodCreationTimestamp;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.getServicePort;
import static oracle.weblogic.kubernetes.actions.TestActions.now;
import static oracle.weblogic.kubernetes.actions.TestActions.patchDomainResourceWithNewIntrospectVersion;
import static oracle.weblogic.kubernetes.actions.impl.Domain.patchDomainCustomResource;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.verifyRollingRestartOccurred;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.callWebAppAndWaitTillReady;
import static oracle.weblogic.kubernetes.utils.AuxiliaryImageUtils.createAndPushAuxiliaryImage;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.checkWeblogicMBean;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createDomainResourceWithAuxiliaryImage;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.replaceConfigMapWithModelFiles;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyIntrospectorRuns;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyPodIntrospectVersionUpdated;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyPodsNotRolled;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.exeAppInServerPod;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getHostAndPort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNextFreePort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.startPortForwardProcess;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.stopPortForwardProcess;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.withStandardRetryPolicy;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createTestRepoSecret;
import static oracle.weblogic.kubernetes.utils.OKDUtils.createRouteForOKD;
import static oracle.weblogic.kubernetes.utils.OKDUtils.setTargetPortForRoute;
import static oracle.weblogic.kubernetes.utils.OKDUtils.setTlsTerminationForRoute;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.PodUtils.getExternalServicePodName;
import static oracle.weblogic.kubernetes.utils.PodUtils.getPodCreationTime;
import static oracle.weblogic.kubernetes.utils.PodUtils.setPodAntiAffinity;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretsForImageRepos;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test verifies the enablement of ProductionSecureMode in WebLogic Operator
 * environment. Make sure all the servers in the domain comes up and WebLogic
 * console is accessible thru default-admin NodePort service
 * In order to enable ProductionSecureMode in WebLogic Operator environment
 * (a) add channel called `default-admin` to domain resource
 * (b) JAVA_OPTIONS to -Dweblogic.security.SSL.ignoreHostnameVerification=true
 * (c) add ServerStartMode: secure to domainInfo section of model file
 *     Alternativley add SecurityConfiguration/SecureMode to topology section
 * (d) add a SSL Configuration to the server template
 */

@DisplayName("Test Secure NodePort service through admin port and default-admin channel in a mii domain")
@IntegrationTest
@Tag("olcne-mrg")
@Tag("kind-parallel")
@Tag("okd-wls-mrg")
@Tag("oke-gate")
class ItMiiDomainUpgradeToSecureMode {

  private static String opNamespace = null;
  private static String domainNamespace = null;
  private static int replicaCount = 1;
  private static final String domainUid = "domain1";
  private static final String configMapName = "default-admin-configmap";
  private final String adminServerPodName = domainUid + "-adminserver";
  private final String managedServerPrefix = domainUid + "mycluster-ms-";

  private static Path pathToEnableSSLYaml;
  private static LoggingFacade logger = null;
  private static String adminSvcSslPortExtHost = null;
  
  private static final String clusterName = "mycluster";
  private static final String wlSecretName = "weblogic-credentials";
  private static final String encryptionSecretName = "encryptionsecret";  

  /**
   * Install Operator.
   * Create domain resource.
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(2) List<String> namespaces) {
    logger = getLogger();

    // get a new unique opNamespace
    logger.info("Assigning unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    logger.info("Assigning unique namespace for Domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domainNamespace = namespaces.get(1);

    // install and verify operator
    installAndVerifyOperator(opNamespace, domainNamespace);

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    createTestRepoSecret(domainNamespace);
  }

  /**
   * Verify all server pods are running. Verify all k8s services for all servers are created.
   */
  public void beforeEach() {
    logger.info("Check admin service and pod {0} is created in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReadyAndServiceExists(adminServerPodName, domainUid, domainNamespace);
    // check managed server services and pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed server services and pods are created in namespace {0}",
          domainNamespace);
      checkPodReadyAndServiceExists(managedServerPrefix + i, domainUid, domainNamespace);
    }
  }

  /**
   * Test upgrade from 1411 to 1412 with production and secure mode off.
   */
  @Test
  @DisplayName("Verify the secure service through administration port")
  void testUpgrade1411to1412ProdOff() {
    //no changes
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "SSLEnabled=false\n", StandardOpenOption.CREATE);
      Files.writeString(wdtVariableFile, "ServerTemp.myserver-template.ListenAddress=8002\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "ProductionModeEnabled=false\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "SecureModeEnabled=false\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "AdministrationPortEnabled=false\n", StandardOpenOption.APPEND);
    });

    String auxImageName = "dci-securemodeoff";
    String auxImageTag = "v1";
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "upgrade-model.yaml");

    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = WEBLOGIC_IMAGE_NAME + ":" + "14.1.1.0-11";
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage);
    String image1412 = WEBLOGIC_IMAGE_NAME + ":" + "14.1.2.0";
    //upgradeImage(domainNamespace, domainUid, auxImage);

  }
  
  void testUpgrade1411to1412ProdOnSecOff() {
    //no changes

  }

  void testUpgrade1411to1412ProdOnSecOn() {
    //no changes
  }

  void testUpgrade1411to1412ProdOnSecNotConfigured() {
    //convert the domain to explicitly disable Secure Mode for the upgrade so that we retain the current 
    //functionality for the user
  }

  void testUpgrade1214to1412ProdOff() {

  }

  void testUpgrade12214to1412ProdOn() {

  }
  
  /**
   * Create a WebLogic domain with ProductionModeEnabled.
   * Create a domain resource with a channel with the name `default-admin`.
   * Verify a NodePort service is available thru default-admin channel.
   * Verify WebLogic console is accessible through the `default-admin` service.
   * Verify no NodePort service is available thru default channel since
   * clear text default port (7001) is disabled.
   * Check the `default-secure` and `default-admin` port on cluster service.
   * Make sure kubectl port-forward works thru Administration port(9002)
   * Make sure kubectl port-forward does not work thru default SSL Port(7002)
   * when Administration port(9002) is enabled.
   */
  @Test
  @DisplayName("Verify the secure service through administration port")
  void testVerifyProductionSecureMode() {
    int defaultAdminPort = getServiceNodePort(
         domainNamespace, getExternalServicePodName(adminServerPodName), "default-admin");
    assertNotEquals(-1, defaultAdminPort,
          "Could not get the default-admin external service node port");
    logger.info("Found the administration service nodePort {0}", defaultAdminPort);

    // Here the SSL port is explicitly set to 7002 (on-prem default) in
    // in ServerTemplate section on topology file. Here the generated
    // config.xml has no SSL port assigned, but the default-secure service
    // must be active with port 7002
    int defaultClusterSecurePort = assertDoesNotThrow(()
        -> getServicePort(domainNamespace,
              domainUid + "-cluster-cluster-1", "default-secure"),
              "Getting Default Secure Cluster Service port failed");
    assertEquals(7002, defaultClusterSecurePort, "Default Secure Cluster port is not set to 7002");

    int defaultAdminSecurePort = assertDoesNotThrow(()
        -> getServicePort(domainNamespace,
              domainUid + "-cluster-cluster-1", "default-admin"),
              "Getting Default Admin Cluster Service port failed");
    assertEquals(9002, defaultAdminSecurePort, "Default Admin Cluster port is not set to 9002");

    //expose the admin server external service to access the console in OKD cluster
    //set the sslPort as the target port
    adminSvcSslPortExtHost = createRouteForOKD(getExternalServicePodName(adminServerPodName),
                    domainNamespace, "admin-server-sslport-ext");
    setTlsTerminationForRoute("admin-server-sslport-ext", domainNamespace);
    setTargetPortForRoute("admin-server-sslport-ext", domainNamespace, defaultAdminSecurePort);
    String hostAndPort = getHostAndPort(adminSvcSslPortExtHost, defaultAdminPort);
    logger.info("The hostAndPort is {0}", hostAndPort);

    String resourcePath = "/console/login/LoginForm.jsp";
    if (!WEBLOGIC_SLIM) {
      if (OKE_CLUSTER) {
        ExecResult result = exeAppInServerPod(domainNamespace, adminServerPodName,7002, resourcePath);
        logger.info("result in OKE_CLUSTER is {0}", result.toString());
        assertEquals(0, result.exitValue(), "Failed to access WebLogic console");
      } else {
        String curlCmd = "curl -g -sk --show-error --noproxy '*' "
            + " https://" + hostAndPort
            + "/console/login/LoginForm.jsp --write-out %{http_code} "
            + " -o /dev/null";
        logger.info("Executing default-admin nodeport curl command {0}", curlCmd);
        assertTrue(callWebAppAndWaitTillReady(curlCmd, 10));
      }
      logger.info("WebLogic console is accessible thru default-admin service");

      String localhost = "localhost";
      String forwardPort = startPortForwardProcess(localhost, domainNamespace, domainUid, 9002);
      assertNotNull(forwardPort, "port-forward fails to assign local port");
      logger.info("Forwarded admin-port is {0}", forwardPort);
      String curlCmd = "curl -sk --show-error --noproxy '*' "
          + " https://" + localhost + ":" + forwardPort
          + "/console/login/LoginForm.jsp --write-out %{http_code} "
          + " -o /dev/null";
      logger.info("Executing default-admin port-fwd curl command {0}", curlCmd);
      assertTrue(callWebAppAndWaitTillReady(curlCmd, 10));
      logger.info("WebLogic console is accessible thru admin port forwarding");

      // When port-forwarding is happening on admin-port, port-forwarding will
      // not work for SSL port i.e. 7002
      forwardPort = startPortForwardProcess(localhost, domainNamespace, domainUid, 7002);
      assertNotNull(forwardPort, "port-forward fails to assign local port");
      logger.info("Forwarded ssl port is {0}", forwardPort);
      curlCmd = "curl -g -sk --show-error --noproxy '*' "
          + " https://" + localhost + ":" + forwardPort
          + "/console/login/LoginForm.jsp --write-out %{http_code} "
          + " -o /dev/null";
      logger.info("Executing default-admin port-fwd curl command {0}", curlCmd);
      assertFalse(callWebAppAndWaitTillReady(curlCmd, 10));
      logger.info("WebLogic console should not be accessible thru ssl port forwarding");
      stopPortForwardProcess(domainNamespace);
    } else {
      logger.info("Skipping WebLogic console in WebLogic slim image");
    }

    int nodePort = getServiceNodePort(
        domainNamespace, getExternalServicePodName(adminServerPodName), "default");
    assertEquals(-1, nodePort,
        "Default external service node port service must not be available");
    logger.info("Default service nodePort is not available as expected");
  }

  /**
   * Test dynamic update in a domain with secure mode enabled.
   * Specify SSL related environment variables in serverPod for JAVA_OPTIONS and WLSDEPLPOY_PROPERTIES
   * e.g.
   * - name:  WLSDEPLOY_PROPERTIES
   *   value: "-Dweblogic.security.SSL.ignoreHostnameVerification=true -Dweblogic.security.TrustKeyStore=DemoTrust"
   * - name:  JAVA_OPTIONS
   *    value: "-Dweblogic.security.SSL.ignoreHostnameVerification=true -Dweblogic.security.TrustKeyStore=DemoTrust"
   * Create a configmap containing both the model yaml, and a sparse model file to add
   * a new work manager, a min threads constraint, and a max threads constraint.
   * Patch the domain resource with the configmap.
   * Update the introspect version of the domain resource.
   * Verify new work manager is configured.
   * Verify the pods are not restarted.
   * Verify the introspect version is updated.
   */
  @Test
  @DisplayName("Verify MII dynamic update with SSL enabled")
  void testMiiDynamicChangeWithSSLEnabled() {

    LinkedHashMap<String, OffsetDateTime> pods = new LinkedHashMap<>();

    // get the creation time of the admin server pod before patching
    pods.put(adminServerPodName, getPodCreationTime(domainNamespace, adminServerPodName));
    // get the creation time of the managed server pods before patching
    for (int i = 1; i <= replicaCount; i++) {
      pods.put(managedServerPrefix + i, getPodCreationTime(domainNamespace, managedServerPrefix + i));
    }

    replaceConfigMapWithModelFiles(configMapName, domainUid, domainNamespace,
        Arrays.asList(pathToEnableSSLYaml.toString(), MODEL_DIR + "/model.config.wm.yaml"), withStandardRetryPolicy);

    String introspectVersion = patchDomainResourceWithNewIntrospectVersion(domainUid, domainNamespace);

    verifyIntrospectorRuns(domainUid, domainNamespace);

    String resourcePath = "/management/weblogic/latest/domainRuntime/serverRuntimes/"
        + MANAGED_SERVER_NAME_BASE + "1"
        + "/applicationRuntimes/" + MII_BASIC_APP_DEPLOYMENT_NAME
        + "/workManagerRuntimes/newWM";
    if (OKE_CLUSTER) {
      ExecResult result = exeAppInServerPod(domainNamespace, managedServerPrefix + "1",9002, resourcePath);
      logger.info("result in OKE_CLUSTER is {0}", result.toString());
      assertEquals(0, result.exitValue(), "Failed to access WebLogic console");
    } else {
      testUntil(
          () -> checkWeblogicMBean(
              adminSvcSslPortExtHost,
              domainNamespace,
              adminServerPodName,
              "/management/weblogic/latest/domainRuntime/serverRuntimes/"
                  + MANAGED_SERVER_NAME_BASE + "1"
                  + "/applicationRuntimes/" + MII_BASIC_APP_DEPLOYMENT_NAME
                  + "/workManagerRuntimes/newWM",
              "200", true, "default-admin"),
              logger, "work manager configuration to be updated.");
    }

    logger.info("Found new work manager configuration");
    verifyPodsNotRolled(domainNamespace, pods);
    verifyPodIntrospectVersionUpdated(pods.keySet(), introspectVersion, domainNamespace);
  }

  private static void createDomainResource(
      String domainUid, String domNamespace, String adminSecretName,
      String repoSecretName, String encryptionSecretName,
      int replicaCount, String configmapName) {

    // create the domain CR
    DomainResource domain = new DomainResource()
            .apiVersion(DOMAIN_API_VERSION)
            .kind("Domain")
            .metadata(new V1ObjectMeta()
                    .name(domainUid)
                    .namespace(domNamespace))
            .spec(new DomainSpec()
                    .domainUid(domainUid)
                    .domainHomeSourceType("FromModel")
                    .image(MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG)
                    .imagePullPolicy(IMAGE_PULL_POLICY)
                    .addImagePullSecretsItem(new V1LocalObjectReference()
                            .name(repoSecretName))
                    .webLogicCredentialsSecret(new V1LocalObjectReference()
                            .name(adminSecretName))
                    .includeServerOutInPodLog(true)
                    .serverStartPolicy("IfNeeded")
                    .serverPod(new ServerPod()
                            .addEnvItem(new V1EnvVar()
                                    .name("JAVA_OPTIONS")
                                    .value(SSL_PROPERTIES))
                            .addEnvItem(new V1EnvVar()
                                    .name("WLSDEPLOY_PROPERTIES")
                                    .value(SSL_PROPERTIES))
                            .addEnvItem(new V1EnvVar()
                                    .name("USER_MEM_ARGS")
                                    .value("-Djava.security.egd=file:/dev/./urandom ")))
                    .adminServer(new AdminServer()
                            .adminService(new AdminService()
                                    .addChannelsItem(new Channel()
                                            .channelName("default")
                                            .nodePort(getNextFreePort()))
                                    .addChannelsItem(new Channel()
                                            .channelName("default-admin")
                                            .nodePort(getNextFreePort()))))
                    .configuration(new Configuration()
                            .model(new Model()
                                    .domainType("WLS")
                                    .configMap(configmapName)
                                    .runtimeEncryptionSecret(encryptionSecretName)
                                    .onlineUpdate(new OnlineUpdate()
                                            .enabled(true)))
                            .introspectorJobActiveDeadlineSeconds(300L)));
    setPodAntiAffinity(domain);
    logger.info("Create domain custom resource for domainUid {0} in namespace {1}",
            domainUid, domNamespace);
    boolean domCreated = assertDoesNotThrow(() -> createDomainCustomResource(domain),
            String.format("Create domain custom resource failed with ApiException for %s in namespace %s",
                    domainUid, domNamespace));
    assertTrue(domCreated, String.format("Create domain custom resource failed with ApiException "
                    + "for %s in namespace %s", domainUid, domNamespace));
  }
  
  private DomainResource createDomainUsingAuxiliaryImage(String domainNamespace, String domainUid,
      String baseImage, String auxImage) {
    String clusterName = "mycluster";
    String adminServerPodName = domainUid + "-adminserver";
    String managedServerPrefix = domainUid + "-mycluster-ms-";

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    createSecretWithUsernamePassword(wlSecretName, domainNamespace,
        ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    // create encryption secret
    logger.info("Create encryption secret");
    createSecretWithUsernamePassword(encryptionSecretName, domainNamespace, 
        ENCRYPION_USERNAME_DEFAULT, ENCRYPION_PASSWORD_DEFAULT);

    // admin/managed server name here should match with model yaml
    final String auxiliaryImagePath = "/auxiliary";
    // create domain custom resource using a auxiliary image
    logger.info("Creating domain custom resource with domainUid {0} and auxiliary images {1}",
        domainUid, auxImage);

    DomainResource domainCR
        = createDomainResourceWithAuxiliaryImage(domainUid, domainNamespace,
            baseImage, wlSecretName, createSecretsForImageRepos(domainNamespace),
            encryptionSecretName, auxiliaryImagePath, auxImage);
    domainCR.getSpec().getServerPod()
        .addEnvItem(new V1EnvVar()
            .name("JAVA_OPTIONS")
            .value(SSL_PROPERTIES))
        .addEnvItem(new V1EnvVar()
            .name("WLSDEPLOY_PROPERTIES")
            .value(SSL_PROPERTIES));

    //domainCR = createClusterResourceAndAddReferenceToDomain(
    //    clusterName, clusterName, domainNamespace, domainCR, replicaCount);

    // create domain and verify its running
    logger.info("Creating domain {0} with auxiliary images {1} in namespace {2}",
        domainUid, auxImage, domainNamespace);
    createDomainAndVerify(domainUid, domainCR, domainNamespace,
        adminServerPodName, managedServerPrefix, replicaCount);

    return domainCR;
  }
  
  private String createAuxImage(String imageName, String imageTag, String wdtModelFile, String wdtVariableFile) {
    // build app
    AppParams appParams = defaultAppParams()
        .srcDirList(Collections.singletonList(MII_BASIC_APP_NAME))
        .appArchiveDir(ARCHIVE_DIR + this.getClass().getSimpleName())
        .appName(MII_BASIC_APP_NAME);
    assertTrue(buildAppArchive(appParams),
        String.format("Failed to create app archive for %s", MII_BASIC_APP_NAME));
    List<String> archiveList = Collections.singletonList(appParams.appArchiveDir() + "/" + MII_BASIC_APP_NAME + ".zip");
    
    WitParams witParams
        = new WitParams()
            .modelImageName(imageName)
            .modelImageTag(imageTag)
            .modelFiles(Arrays.asList(wdtModelFile))
            .modelVariableFiles(Arrays.asList(wdtVariableFile))
            .modelArchiveFiles(archiveList);
    createAndPushAuxiliaryImage(imageName, imageTag, witParams);
    
    return imageName + ":" + imageTag;
  }
  
  private void upgradeImage(String domainNamespace, String domainUid, String newImage) {
    // get the original domain resource before update
    DomainUtils.getAndValidateInitialDomain(domainNamespace, domainUid);

    // get the map with server pods and their original creation timestamps
    Map<String, OffsetDateTime> podsWithTimeStamps = getPodsWithTimeStamps(domainNamespace, domainUid);

    OffsetDateTime timestamp = now();

    logger.info("patch the domain resource with new image");
    String patchStr
        = "["
        + "{\"op\": \"replace\", \"path\": \"/spec/image\", "
        + "\"value\": \"" + newImage + "\"}"
        + "]";
    logger.info("Updating domain configuration using patch string: {0}\n", patchStr);
    V1Patch patch = new V1Patch(patchStr);
    assertTrue(patchDomainCustomResource(domainUid, domainNamespace, patch, V1Patch.PATCH_FORMAT_JSON_PATCH),
        "Failed to patch domain");

    // verify the server pods are rolling restarted and back to ready state
    logger.info("Verifying rolling restart occurred for domain {0} in namespace {1}",
        domainUid, domainNamespace);
    assertTrue(verifyRollingRestartOccurred(podsWithTimeStamps, 1, domainNamespace),
        String.format("Rolling restart failed for domain %s in namespace %s", domainUid, domainNamespace));
  }
  
  
  @SuppressWarnings("unchecked")
  private <K, V> Map<K, V> getPodsWithTimeStamps(String domainNamespace, String domainUid) {
    String adminServerPodName = domainUid + "-adminserver";
    String managedServerPrefix = domainUid + "-mycluster-ms-";

    // create the map with server pods and their original creation timestamps
    Map<String, OffsetDateTime> podsWithTimeStamps = new LinkedHashMap<>();
    podsWithTimeStamps.put(adminServerPodName,
        assertDoesNotThrow(() -> getPodCreationTimestamp(domainNamespace, "", adminServerPodName),
            String.format("getPodCreationTimestamp failed with ApiException for pod %s in namespace %s",
                adminServerPodName, domainNamespace)));

    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = managedServerPrefix + i;
      podsWithTimeStamps.put(managedServerPodName,
          assertDoesNotThrow(() -> getPodCreationTimestamp(domainNamespace, "", managedServerPodName),
              String.format("getPodCreationTimestamp failed with ApiException for pod %s in namespace %s",
                  managedServerPodName, domainNamespace)));
    }
    return (Map<K, V>) podsWithTimeStamps;
  }
  
}
