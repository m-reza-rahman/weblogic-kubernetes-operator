// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;

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
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.primitive.Command;
import oracle.weblogic.kubernetes.actions.impl.primitive.CommandParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.CleanupUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.DEFAULT_EXTERNAL_SERVICE_NAME_SUFFIX;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.ENCRYPION_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ENCRYPION_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.KUBERNETES_CLI;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.OLD_DOMAIN_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.RESULTS_ROOT;
import static oracle.weblogic.kubernetes.TestConstants.SKIP_CLEANUP;
import static oracle.weblogic.kubernetes.TestConstants.SSL_PROPERTIES;
import static oracle.weblogic.kubernetes.TestConstants.TEST_IMAGES_REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WDT_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WDT_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.createDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.scaleCluster;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.verifyAdminConsoleAccessible;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyPodsNotRolled;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNextFreePort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.startPortForwardProcess;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.stopPortForwardProcess;
import static oracle.weblogic.kubernetes.utils.FileUtils.replaceStringInFile;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createTestRepoSecret;
import static oracle.weblogic.kubernetes.utils.PatchDomainUtils.patchServerStartPolicy;
import static oracle.weblogic.kubernetes.utils.PodUtils.checkPodDeleted;
import static oracle.weblogic.kubernetes.utils.PodUtils.checkPodReady;
import static oracle.weblogic.kubernetes.utils.PodUtils.getExternalServicePodName;
import static oracle.weblogic.kubernetes.utils.PodUtils.getPodCreationTime;
import static oracle.weblogic.kubernetes.utils.PodUtils.setPodAntiAffinity;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static oracle.weblogic.kubernetes.utils.UpgradeUtils.checkDomainStatus;
import static oracle.weblogic.kubernetes.utils.UpgradeUtils.cleanUpCRD;
import static oracle.weblogic.kubernetes.utils.UpgradeUtils.installOldOperator;
import static oracle.weblogic.kubernetes.utils.UpgradeUtils.upgradeOperatorToCurrent;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Install a released version of Operator from GitHub chart repository.
 * Create a domain using Domain-In-Image and Model-In-Image model with a dynamic cluster.
 * Deploy an application to the cluster in domain and verify the application
 * can be accessed while the operator is upgraded and after the upgrade.
 * Upgrade operator with current Operator image build from current branch.
 * Verify Domain resource version and image are updated.
 * Scale the cluster in upgraded environment.
 * Restart the entire domain in upgraded environment.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Operator upgrade tests with Multiple Domains")
@IntegrationTest
@Tag("kind-sequential")
class ItOperatorMultiWlsUpgrade {

  private static LoggingFacade logger = null;
  private String domainUid = "domain1";
  private String domainUid2 = "domain2";
  private String adminServerPodName = domainUid + "-admin-server";
  private String managedServerPodNamePrefix = domainUid + "-managed-server";
  private String adminServerPodName2 = domainUid2 + "-admin-server";
  private String managedServerPodNamePrefix2 = domainUid2 + "-managed-server";
  private int replicaCount = 2;
  private List<String> namespaces;
  private String latestOperatorImageName;
  private String adminSecretName = "weblogic-credentials";
  private String encryptionSecretName = "encryptionsecret";
  private String opNamespace;
  private String domainNamespace;
  private String domainNamespace2;
  private Path srcDomainYaml = null;
  private Path destDomainYaml = null;

  /**
   * For each test:
   * Assigns unique namespaces for operator and domain.
   * @param namespaces injected by JUnit
   */
  @BeforeEach
  public void beforeEach(@Namespaces(3) List<String> namespaces) {
    this.namespaces = namespaces;
    assertNotNull(namespaces.get(0), "Namespace is null");
    opNamespace = namespaces.get(0);
    logger.info("Assign a unique namespace for domain(s)");
    assertNotNull(namespaces.get(1), "Namespace is null");
    domainNamespace = namespaces.get(1);
    assertNotNull(namespaces.get(2), "Namespace is null");
    domainNamespace2 = namespaces.get(2);
  }

  /**
   * Does some initialization of logger, conditionfactory, etc common
   * to all test methods.
   */
  @BeforeAll
  public static void init() {
    logger = getLogger();
  }

  /**
   * Operator upgrade from 4.0.5 to current.
   */
  @Test
  @DisplayName("Upgrade Operator from 4.0.5 to current with Muktiple Domains")
  void testOperatorMultiWlsUpgradeFrom405ToCurrent() {
    logger.info("Starting test testOperatorMultiWlsUpgradeFrom405ToCurrent");
    installAndUpgradeOperator("4.0.5", OLD_DOMAIN_VERSION);
  }

  /**
   * Cleanup Kubernetes artifacts in the namespaces used by the test and
   * delete CRD.
   */
  @AfterEach
  public void tearDown() {
    if (!SKIP_CLEANUP) {
      CleanupUtil.cleanup(namespaces);
      cleanUpCRD();
    }
  }

  // After upgrade scale up/down the cluster
  private void scaleClusterUpAndDown() {

    String clusterName = domainUid + "-" + "cluster-1";
    logger.info("Updating the cluster {0} replica count to 3", clusterName);
    boolean p1Success = scaleCluster(clusterName, domainNamespace,3);
    assertTrue(p1Success,
        String.format("Patching replica to 3 failed for cluster %s in namespace %s",
            clusterName, domainNamespace));

    logger.info("Updating the cluster {0} replica count to 2");
    p1Success = scaleCluster(clusterName, domainNamespace,2);
    assertTrue(p1Success,
        String.format("Patching replica to 2 failed for cluster %s in namespace %s",
            clusterName, domainNamespace));
  }

  private void installDomainResource(
      String domainType, String domainVersion, 
      String domainNamespace, String domainUid) {

    logger.info("Default Domain API version {0}", DOMAIN_API_VERSION);
    logger.info("Domain API version selected {0}", domainVersion);
    logger.info("Install domain resource for domainUid {0} in namespace {1}",
            domainUid, domainNamespace);

    // create WLS domain and verify
    createWlsDomainAndVerifyByDomainYaml(domainType,domainNamespace,domainUid);

  }

  private void installAndUpgradeOperator(String operatorVersion, 
         String domainVersion) {

    installOldOperator(operatorVersion,
           opNamespace,domainNamespace,domainNamespace2);
    // create WLS (Domain-in-Image) and WLS (Model-in-Image) domain and verify
    installDomainResource("Image",
          domainVersion,domainNamespace,domainUid);
    installDomainResource("FromModel",
          domainVersion,domainNamespace2,domainUid2);

    LinkedHashMap<String, OffsetDateTime> pods = new LinkedHashMap<>();
    pods.put(adminServerPodName, getPodCreationTime(domainNamespace, adminServerPodName));
    // get the creation time of the managed server pods before patching
    for (int i = 1; i <= replicaCount; i++) {
      pods.put(managedServerPodNamePrefix + i, getPodCreationTime(domainNamespace, managedServerPodNamePrefix + i));
    }

    LinkedHashMap<String, OffsetDateTime> pods2 = new LinkedHashMap<>();
    pods.put(adminServerPodName2, getPodCreationTime(domainNamespace2, adminServerPodName2));
    // get the creation time of the managed server pods before patching
    for (int i = 1; i <= replicaCount; i++) {
      pods.put(managedServerPodNamePrefix2 + i, getPodCreationTime(domainNamespace2, managedServerPodNamePrefix2 + i));
    }
    
    // upgrade the Operator to current version 
    upgradeOperatorToCurrent(opNamespace);

    checkDomainStatus(domainNamespace,domainUid);
    checkDomainStatus(domainNamespace2,domainUid2);

    verifyPodsNotRolled(domainNamespace, pods);
    verifyPodsNotRolled(domainNamespace2, pods2);

    // upgradeOperatorAndVerify(opNamespace, domainNamespace);
  }

  private void createSecrets() {
    // Create the repo secret to pull the domain image
    // this secret is used only for non-kind cluster
    createTestRepoSecret(domainNamespace);

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    createSecretWithUsernamePassword(adminSecretName, domainNamespace,
         ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    logger.info("Create encryption secret");
    createSecretWithUsernamePassword(encryptionSecretName, domainNamespace,
        ENCRYPION_USERNAME_DEFAULT, ENCRYPION_PASSWORD_DEFAULT);
  }

  private void createWlsDomainAndVerify(String domainType,
        String domainNamespace, String domainVersion) {

    createSecrets();

    String domainImage = "";
    if (domainType.equalsIgnoreCase("Image")) {
      domainImage = WDT_BASIC_IMAGE_NAME + ":" + WDT_BASIC_IMAGE_TAG;
    } else {
      domainImage = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;
    }

    // create domain
    createDomainResource(domainNamespace, domainVersion,
                         domainType, domainImage);
    checkDomainStarted(domainUid, domainNamespace);
    logger.info("Getting node port for default channel");
    int serviceNodePort = assertDoesNotThrow(() -> getServiceNodePort(
        domainNamespace, 
        getExternalServicePodName(adminServerPodName, DEFAULT_EXTERNAL_SERVICE_NAME_SUFFIX), "default"),
        "Getting admin server node port failed");
    logger.info("Validating WebLogic admin server access by login to console");
    verifyAdminConsoleAccessible(domainNamespace, K8S_NODEPORT_HOST,
           String.valueOf(serviceNodePort), false);
  }

  private void checkDomainStarted(String domainUid, String domainNamespace) {
    // verify admin server pod is ready
    checkPodReady(adminServerPodName, domainUid, domainNamespace);

    // verify the admin server service created
    checkServiceExists(adminServerPodName, domainNamespace);

    // verify managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Waiting for managed server pod {0} to be ready in namespace {1}",
          managedServerPodNamePrefix + i, domainNamespace);
      checkPodReady(managedServerPodNamePrefix + i, domainUid, domainNamespace);
    }

    // verify managed server services created
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Checking managed server service {0} is created in namespace {1}",
          managedServerPodNamePrefix + i, domainNamespace);
      checkServiceExists(managedServerPodNamePrefix + i, domainNamespace);
    }
  }

  private void checkDomainStopped(String domainUid, String domainNamespace) {
    // verify admin server pod is deleted
    checkPodDeleted(adminServerPodName, domainUid, domainNamespace);
    // verify managed server pods are deleted
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Waiting for managed server pod {0} to be deleted in namespace {1}",
          managedServerPodNamePrefix + i, domainNamespace);
      checkPodDeleted(managedServerPodNamePrefix + i, domainUid, domainNamespace);
    }
  }

  /**
   * Restart the domain after upgrade by changing serverStartPolicy.
   */
  private void restartDomain(String domainUid, String domainNamespace) {
    assertTrue(patchServerStartPolicy(domainUid, domainNamespace,
         "/spec/serverStartPolicy", "Never"),
         "Failed to patch Domain's serverStartPolicy to Never");
    logger.info("Domain is patched to shutdown");
    checkDomainStopped(domainUid, domainNamespace);

    assertTrue(patchServerStartPolicy(domainUid, domainNamespace,
         "/spec/serverStartPolicy", "IfNeeded"),
         "Failed to patch Domain's serverStartPolicy to IfNeeded");
    logger.info("Domain is patched to re start");
    checkDomainStarted(domainUid, domainNamespace);
  }

  private void createDomainResource(
      String domainNamespace,
      String domVersion,
      String domainHomeSourceType,
      String domainImage) {

    String domApiVersion = "weblogic.oracle/" + domVersion;
    logger.info("Default Domain API version {0}", DOMAIN_API_VERSION);
    logger.info("Domain API version selected {0}", domApiVersion);
    logger.info("Domain Image name selected {0}", domainImage);
    logger.info("Create domain resource for domainUid {0} in namespace {1}",
            domainUid, domainNamespace);

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    createSecretWithUsernamePassword(encryptionSecretName, domainNamespace,
                      "weblogicenc", "weblogicenc");
    DomainResource domain = new DomainResource()
            .apiVersion(domApiVersion)
            .kind("Domain")
            .metadata(new V1ObjectMeta()
                    .name(domainUid)
                    .namespace(domainNamespace))
            .spec(new DomainSpec()
                    .domainUid(domainUid)
                    .domainHomeSourceType(domainHomeSourceType)
                    .image(domainImage)
                    .addImagePullSecretsItem(new V1LocalObjectReference()
                            .name(TEST_IMAGES_REPO_SECRET_NAME))
                    .webLogicCredentialsSecret(new V1LocalObjectReference()
                            .name(adminSecretName))
                    .includeServerOutInPodLog(true)
                    .serverStartPolicy("weblogic.oracle/v8".equals(domApiVersion) ? "IF_NEEDED" : "IfNeeded")
                    .serverPod(new ServerPod()
                            .addEnvItem(new V1EnvVar()
                                    .name("JAVA_OPTIONS")
                                    .value(SSL_PROPERTIES))
                            .addEnvItem(new V1EnvVar()
                                    .name("USER_MEM_ARGS")
                                    .value("-Djava.security.egd=file:/dev/./urandom ")))
                    .adminServer(new AdminServer()
                        .adminService(new AdminService()
                        .addChannelsItem(new Channel()
                        .channelName("default")
                        .nodePort(getNextFreePort()))))
                    .configuration(new Configuration()
                            .model(new Model()
                                .runtimeEncryptionSecret(encryptionSecretName)
                                .domainType("WLS"))
                            .introspectorJobActiveDeadlineSeconds(300L)));
    boolean domCreated = assertDoesNotThrow(() -> createDomainCustomResource(domain, domVersion),
          String.format("Create domain custom resource failed with ApiException for %s in namespace %s",
          domainUid, domainNamespace));
    assertTrue(domCreated,
         String.format("Create domain custom resource failed with ApiException "
             + "for %s in namespace %s", domainUid, domainNamespace));
    setPodAntiAffinity(domain);
    removePortForwardingAttribute(domainNamespace,domainUid);
  }

  // Remove the artifact adminChannelPortForwardingEnabled from domain resource
  // if exist, so that the Operator release default will be effective.
  // e.g. in Release 3.3.x the default is false, but 4.x.x onward it is true
  // However in release(s) lower to 3.3.x, the CRD does not contain this attribute
  // so the patch command to remove this attribute fails. So we do not assert
  // the result of patch command
  // assertTrue(result, "Failed to remove PortForwardingAttribute");
  private void removePortForwardingAttribute(
      String domainNamespace, String  domainUid) {

    StringBuffer patchStr = new StringBuffer("[{");
    patchStr.append("\"op\": \"remove\",")
        .append(" \"path\": \"/spec/adminServer/adminChannelPortForwardingEnabled\"")
        .append("}]");
    logger.info("The patch String {0}", patchStr);
    StringBuffer commandStr = new StringBuffer(KUBERNETES_CLI + " patch domain ");
    commandStr.append(domainUid)
              .append(" -n " + domainNamespace)
              .append(" --type 'json' -p='")
              .append(patchStr)
              .append("'");
    logger.info("The Command String: {0}", commandStr);
    CommandParams params = new CommandParams().defaults();

    params.command(new String(commandStr));
    boolean result = Command.withParams(params).execute();
  }

  private void checkAdminPortForwarding(String domainNamespace, boolean successExpected) {

    logger.info("Checking port forwarding [{0}]", successExpected);
    String forwardPort =
           startPortForwardProcess("localhost", domainNamespace,
           domainUid, 7001);
    assertNotNull(forwardPort, "port-forward fails to assign local port");
    logger.info("Forwarded admin-port is {0}", forwardPort);
    if (successExpected) {
      verifyAdminConsoleAccessible(domainNamespace, "localhost",
           forwardPort, false);
      logger.info("WebLogic console is accessible thru port forwarding");
    } else {
      verifyAdminConsoleAccessible(domainNamespace, "localhost",
           forwardPort, false, false);
      logger.info("WebLogic console shouldn't accessible thru port forwarding");
    }
    stopPortForwardProcess(domainNamespace);
  }

  /**
   * Replace the fields in domain yaml file with testing attributes.
   * For example, namespace, domainUid,  and image. Then create domain using
   * KUBERNETES_CLI and verify the domain is created
   * @param domainType either domain in image(Image) or model in image (FromModel)
   * @param domainNamespace namespace in which to create domain
   * @param domainUid  unique domain Identifier  for domain resource
   */
  private void createWlsDomainAndVerifyByDomainYaml(String domainType,
      String domainNamespace, String domainUid) {

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    createSecrets();

    // use the checked in domain.yaml to create domain for old releases
    // copy domain.yaml to results dir
    assertDoesNotThrow(() -> Files.createDirectories(
        Paths.get(RESULTS_ROOT + "/" + this.getClass().getSimpleName())),
        String.format("Could not create directory under %s", RESULTS_ROOT));

    if (domainType.equalsIgnoreCase("Image")) {
      logger.info("Domain home in image domain will be created ");
      srcDomainYaml = Paths.get(RESOURCE_DIR, "domain", "domain-v8.yaml");
      destDomainYaml =
        Paths.get(RESULTS_ROOT + "/" + this.getClass().getSimpleName() + "/" + "domain.yaml");
      assertDoesNotThrow(() -> Files.copy(srcDomainYaml, destDomainYaml, REPLACE_EXISTING),
          "File copy failed for domain-v8.yaml");
    } else {
      logger.info("Model in image domain will be created ");
      srcDomainYaml = Paths.get(RESOURCE_DIR, "domain", "mii-domain-v8.yaml");
      destDomainYaml =
        Paths.get(RESULTS_ROOT + "/" + this.getClass().getSimpleName() + "/" + "mii-domain.yaml");
      assertDoesNotThrow(() -> Files.copy(srcDomainYaml, destDomainYaml, REPLACE_EXISTING),
          "File copy failed for mii-domain-v8.yaml");
    }

    // replace namespace, domainUid,  and image in domain.yaml
    assertDoesNotThrow(() -> replaceStringInFile(
        destDomainYaml.toString(), "domain1-ns", domainNamespace),
        "Could not modify the namespace in the domain.yaml file");
    assertDoesNotThrow(() -> replaceStringInFile(
        destDomainYaml.toString(), "domain1", domainUid),
        "Could not modify the domainUid in the domain.yaml file");
    assertDoesNotThrow(() -> replaceStringInFile(
        destDomainYaml.toString(), "domain1-weblogic-credentials", adminSecretName),
        "Could not modify the webLogicCredentialsSecret in the domain.yaml file");
    if (domainType.equalsIgnoreCase("Image")) {
      assertDoesNotThrow(() -> replaceStringInFile(
          destDomainYaml.toString(), "domain-home-in-image:14.1.1.0",
          WDT_BASIC_IMAGE_NAME + ":" + WDT_BASIC_IMAGE_TAG),
          "Could not modify image name in the domain.yaml file");
    } else {
      assertDoesNotThrow(() -> replaceStringInFile(
          destDomainYaml.toString(), "domain1-runtime-encryption-secret", encryptionSecretName),
          "Could not modify runtimeEncryptionSecret in the domain-v8.yaml file");
      assertDoesNotThrow(() -> replaceStringInFile(
          destDomainYaml.toString(), "model-in-image:WLS-v1",
          MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG),
          "Could not modify image name in the mii-domain-v8.yaml file");
    }
    assertTrue(new Command()
        .withParams(new CommandParams()
            .command(KUBERNETES_CLI + " create -f " + destDomainYaml))
        .execute(), KUBERNETES_CLI + " create failed");

    verifyDomain(domainUid, domainNamespace);

  }

  private void verifyDomain(String domainUidString, String domainNamespace) {

    checkDomainStarted(domainUid, domainNamespace);

    logger.info("Getting node port for default channel");
    int serviceNodePort = assertDoesNotThrow(() -> getServiceNodePort(
        domainNamespace, 
        getExternalServicePodName(adminServerPodName, DEFAULT_EXTERNAL_SERVICE_NAME_SUFFIX), "default"),
        "Getting admin server node port failed");
    logger.info("Got node port {0} for default channel for domainNameSpace {1}", serviceNodePort, domainNamespace);

    logger.info("Validating WebLogic admin server access by login to console");
    verifyAdminConsoleAccessible(domainNamespace, K8S_NODEPORT_HOST,
           String.valueOf(serviceNodePort), false);

  }

}
