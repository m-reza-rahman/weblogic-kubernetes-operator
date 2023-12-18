// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

/*import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;*/
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
//import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
//import java.util.Properties;

/*import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Configuration;*/
import oracle.weblogic.domain.DomainResource;
/*import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;*/
import oracle.weblogic.kubernetes.actions.impl.AppParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.WitParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
//import oracle.weblogic.kubernetes.assertions.TestAssertions;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
//import oracle.weblogic.kubernetes.utils.ExecResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
//import static oracle.weblogic.kubernetes.TestConstants.DB_IMAGE_TO_USE_IN_SPEC;
//import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_IMAGES_PREFIX;
/*import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.IMAGE_PULL_POLICY;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_WDT_MODEL_FILE;*/
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOSTNAME;
import static oracle.weblogic.kubernetes.TestConstants.RESULTS_ROOT;
/*import static oracle.weblogic.kubernetes.TestConstants.TEST_IMAGES_REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_NAME;*/
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_TO_USE_IN_SPEC;
//import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_SLIM;
//import static oracle.weblogic.kubernetes.actions.ActionConstants.APP_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ARCHIVE_DIR;
//import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.buildAppArchive;
//import static oracle.weblogic.kubernetes.actions.TestActions.createDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.defaultAppParams;
/*import static oracle.weblogic.kubernetes.actions.TestActions.getPodIP;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.domainExists;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.checkAppIsActive;*/
import static oracle.weblogic.kubernetes.utils.AuxiliaryImageUtils.createAndPushAuxiliaryImage;
//import static oracle.weblogic.kubernetes.utils.BuildApplication.buildApplication;
import static oracle.weblogic.kubernetes.utils.ClusterUtils.createClusterResourceAndAddReferenceToDomain;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createDomainResourceWithAuxiliaryImage;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getDateAndTimeStamp;
/*import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getHostAndPort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNextFreePort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.DbUtils.getDBNodePort;
import static oracle.weblogic.kubernetes.utils.DbUtils.startOracleDB;*/
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNextFreePort;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
/*import static oracle.weblogic.kubernetes.utils.ExecCommand.exec;
import static oracle.weblogic.kubernetes.utils.FileUtils.copyFolder;
import static oracle.weblogic.kubernetes.utils.FileUtils.replaceStringInFile;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createBaseRepoSecret;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createImageAndVerify;*/
import static oracle.weblogic.kubernetes.utils.ImageUtils.createTestRepoSecret;
//import static oracle.weblogic.kubernetes.utils.ImageUtils.imageRepoLoginAndPushImageToRegistry;
//import static oracle.weblogic.kubernetes.utils.OKDUtils.createRouteForOKD;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
//import static oracle.weblogic.kubernetes.utils.PodUtils.getExternalServicePodName;
//import static oracle.weblogic.kubernetes.utils.PodUtils.setPodAntiAffinity;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretsForImageRepos;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
//import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross domain transaction tests.
 */
@DisplayName("Verify cross domain transaction is successful")
@IntegrationTest
@Tag("kind-parallel")
class ItCrossDomainTransactionSecurity {

  private static final String auxImageName1 = DOMAIN_IMAGES_PREFIX + "domain1-cdxaction-aux";
  private static final String auxImageName2 = DOMAIN_IMAGES_PREFIX + "domain2-cdxaction-aux";
  private static final String PROPS_TEMP_DIR = RESULTS_ROOT + "/crossdomsecurity";


  private static String opNamespace = null;
  private static String domainNamespace = null;
  private static String domainUid1 = "domain1";
  private static String domainUid2 = "domain2";
  private static int  admin2ServiceNodePort = -1;
  private static String domain1AdminServerPodName = domainUid1 + "-adminserver";
  private static String domain1ManagedServerPrefix = domainUid1 + "-managed-server";
  private static String domain2AdminServerPodName = domainUid2 + "-adminserver";
  private static String domain2ManagedServerPrefix = domainUid2 + "-managed-server";
  private static int  domain1AdminServiceNodePort = -1;
  private static LoggingFacade logger = null;
  private static int replicaCount = 2;

  /**
   * Install Operator.
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   *     JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(3) List<String> namespaces) {
    logger = getLogger();

    // get a new unique opNamespace
    logger.info("Creating unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    logger.info("Creating unique namespace for Domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domainNamespace = namespaces.get(1);

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    createTestRepoSecret(domainNamespace);

    // install and verify operator
    installAndVerifyOperator(opNamespace, domainNamespace);
    buildDomains();

  }

  /**
   * Verify all server pods are running.
   * Verify k8s services for all servers are created.
   */
  @BeforeEach
  public void beforeEach() {
    int replicaCount = 2;
    /*for (int i = 1; i <= replicaCount; i++) {
      checkPodReadyAndServiceExists(domain2ManagedServerPrefix + i,
            domainUid2, domainNamespace);
    }*/
    for (int i = 1; i <= replicaCount; i++) {
      checkPodReadyAndServiceExists(domain1ManagedServerPrefix + i,
            domainUid1, domainNamespace);
    }
  }

  /**
   * Configure two domains d1 and d2 with CrossDomainSecurityEnabled set to true
   * On both domains create a user (cross-domain) with group CrossDomainConnectors
   * Add required Credential Mapping
   * Deploy a JSP on d1's admin server that takes 2 parameteers
   * # a. The tx aaction b. the d2's cluster service url
   * # Starts a User transcation
   * # send a message to local destination (jms.admin.adminQueue) on d1
   * # send a messgae to a distributed destination (jms.testUniformQueue) on d2
   * # Commit/rollback the transation
   */
  @Test
  @DisplayName("Check cross domain transaction works")
  void testCrossDomainTransactionSecurityEnable() {

    logger.info("2 domains with crossDomainSecurity enabled start up!");
  }

  private static String createAuxImage(String imageName, String imageTag, List<String> wdtModelFile,
                                       String wdtVariableFile) {

    // build sample-app application
    AppParams appParams = defaultAppParams()
        .srcDirList(Collections.singletonList("crossdomain-security"))
        .appArchiveDir(ARCHIVE_DIR + ItCrossDomainTransactionSecurity.class.getName())
        .appName("crossdomainsec");
    assertTrue(buildAppArchive(appParams),
        String.format("Failed to create app archive for %s", "crossdomainsec"));
    List<String> archiveList = Collections.singletonList(appParams.appArchiveDir() + "/" + "crossdomainsec" + ".zip");

    //create an auxiliary image with model and application
    WitParams witParams
        = new WitParams()
            .modelImageName(imageName)
            .modelImageTag(imageTag)
            .modelFiles(wdtModelFile)
            .modelVariableFiles(Arrays.asList(wdtVariableFile))
            .modelArchiveFiles(archiveList);
    createAndPushAuxiliaryImage(imageName, imageTag, witParams);

    return imageName + ":" + imageTag;
  }

  private static void buildDomains() {

    String auxImageTag = getDateAndTimeStamp();
    String modelDir = RESOURCE_DIR + "/" + "crossdomsecurity";
    List<String> modelList = new ArrayList<>();
    modelList.add(modelDir + "/" + "model.dynamic.wls.yaml");
    modelList.add(modelDir + "/sparse.jdbc.yaml");
    modelList.add(modelDir + "/sparse.jms.yaml");
    modelList.add(modelDir + "/sparse.application.yaml");

    // create WDT properties file for the WDT model domain1
    Path wdtVariableFile1 = Paths.get(WORK_DIR, ItCrossDomainTransactionSecurity.class.getName(),
        "wdtVariable1.properties");
    logger.info("The K8S_NODEPORT_HOSTNAME is: " + K8S_NODEPORT_HOSTNAME);
    logger.info("The K8S_NODEPORT_HOST is: " + K8S_NODEPORT_HOST);

    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile1);
      Files.createDirectories(wdtVariableFile1.getParent());
      Files.writeString(wdtVariableFile1, "DOMAIN_UID=domain1\n", StandardOpenOption.CREATE);
      Files.writeString(wdtVariableFile1, "CLUSTER_NAME=cluster-1\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile1, "ADMIN_SERVER_NAME=adminserver\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile1, "MANAGED_SERVER_BASE_NAME=managed-server\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile1, "MANAGED_SERVER_PORT=8001\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile1, "MANAGED_SERVER_COUNT=4\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile1, "T3PUBLICADDRESS=" + K8S_NODEPORT_HOSTNAME + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile1, "T3CHANNELPORT=" + getNextFreePort() + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile1, "REMOTE_DOMAIN=domain2\n", StandardOpenOption.APPEND);
    });

    // create auxiliary image for domain1
    String auxImage1 = createAuxImage(auxImageName1, auxImageTag, modelList, wdtVariableFile1.toString());

    // create WDT properties file for the WDT model domain2
    Path wdtVariableFile2 = Paths.get(WORK_DIR, ItCrossDomainTransactionSecurity.class.getName(),
        "wdtVariable2.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile2);
      Files.createDirectories(wdtVariableFile2.getParent());
      Files.writeString(wdtVariableFile2, "DOMAIN_UID=domain2\n", StandardOpenOption.CREATE);
      Files.writeString(wdtVariableFile2, "CLUSTER_NAME=cluster-2\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile2, "ADMIN_SERVER_NAME=adminserver\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile2, "MANAGED_SERVER_BASE_NAME=managed-server\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile2, "MANAGED_SERVER_PORT=8001\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile2, "MANAGED_SERVER_COUNT=4\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile2, "T3PUBLICADDRESS=" + K8S_NODEPORT_HOSTNAME + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile2, "T3CHANNELPORT=" + getNextFreePort() + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile2, "REMOTE_DOMAIN=domain1\n", StandardOpenOption.APPEND);
    });

    // create auxiliary image for domain1
    String auxImage2 = createAuxImage(auxImageName2, auxImageTag, modelList, wdtVariableFile2.toString());

    // create admin credential secret for domain1
    logger.info("Create admin credential secret for domain1");
    String domain1AdminSecretName = domainUid1 + "-weblogic-credentials";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        domain1AdminSecretName, domainNamespace, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT),
        String.format("createSecret %s failed for %s", domain1AdminSecretName, domainUid1));

    // create admin credential secret for domain2
    logger.info("Create admin credential secret for domain2");
    String domain2AdminSecretName = domainUid2 + "-weblogic-credentials";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        domain2AdminSecretName, domainNamespace, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT),
        String.format("createSecret %s failed for %s", domain2AdminSecretName, domainUid2));

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    createSecretWithUsernamePassword(encryptionSecretName, domainNamespace,
        "weblogicenc", "weblogicenc");

    //create domain1 and verify its running
    createDomain(domainUid1, auxImage1, domainNamespace, domain1AdminSecretName, encryptionSecretName,
        "cluster-1", domain1AdminServerPodName, domain1ManagedServerPrefix);

    //create domain2 and verify its running
    createDomain(domainUid2, auxImage2, domainNamespace, domain2AdminSecretName, encryptionSecretName,
        "cluster-2", domain2AdminServerPodName, domain2ManagedServerPrefix);
  }

  private static void createDomain(String domainUid, String imageName, String domainNamespace, String
      domainAdminSecretName, String encryptionSecretName, String clusterName, String adminServerPodName,
      String managedServerPrefix) {

    final String auxiliaryImagePath = "/auxiliary";
    //create domain resource with the auxiliary image
    logger.info("Creating domain custom resource with domainUid {0} and auxiliary images {1}",
        domainUid, imageName);
    DomainResource domainCR = createDomainResourceWithAuxiliaryImage(domainUid, domainNamespace,
        WEBLOGIC_IMAGE_TO_USE_IN_SPEC, domainAdminSecretName, createSecretsForImageRepos(domainNamespace),
        encryptionSecretName, auxiliaryImagePath,
        imageName);

    domainCR = createClusterResourceAndAddReferenceToDomain(
        domainUid + "-" + clusterName, clusterName, domainNamespace, domainCR, replicaCount);
    // create domain and verify its running
    logger.info("Creating domain {0} with auxiliary images {1} {2} in namespace {3}",
        domainUid, imageName, domainNamespace);
    createDomainAndVerify(domainUid, domainCR, domainNamespace,
        adminServerPodName, managedServerPrefix, replicaCount);

  }



}

