// Copyright (c) 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.util.Yaml;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.DomainResource;
import oracle.weblogic.kubernetes.actions.impl.AppParams;
import oracle.weblogic.kubernetes.actions.impl.NginxParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.WitParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ExecCommand;
import oracle.weblogic.kubernetes.utils.ExecResult;
import oracle.weblogic.kubernetes.utils.ImageUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.BASE_IMAGES_PREFIX;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_IMAGES_PREFIX;
import static oracle.weblogic.kubernetes.TestConstants.ENCRYPION_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ENCRYPION_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.KUBERNETES_CLI;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_NAME;
import static oracle.weblogic.kubernetes.TestConstants.SSL_PROPERTIES;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_NAME_DEFAULT;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ARCHIVE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.buildAppArchive;
import static oracle.weblogic.kubernetes.actions.TestActions.defaultAppParams;
import static oracle.weblogic.kubernetes.actions.TestActions.getDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.getPod;
import static oracle.weblogic.kubernetes.actions.TestActions.listDomainCustomResources;
import static oracle.weblogic.kubernetes.actions.TestActions.shutdownDomain;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.podDoesNotExist;
import static oracle.weblogic.kubernetes.utils.AuxiliaryImageUtils.createAndPushAuxiliaryImage;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createDomainResourceWithAuxiliaryImage;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getDateAndTimeStamp;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretsForImageRepos;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test verifies various secure domains using 1412 image.
 * Verify different combinations of production secure domain can start
 * REST management interfaces are accessible thru appropriate channels.
 * Verify deployed customer applications are accessible in appropriate channels and ports.
 */
@DisplayName("Test secure domains with 1412 image for a mii domain")
@IntegrationTest
@Tag("kind-parallel")
class ItSecureModeDomain {

  private static List<String> namespaces;
  private static String opNamespace;
  private static String ingressNamespace;  
  private static String domainNamespace;
  private static final int replicaCount = 1;
  private static String domainUid;
  private static final String adminServerName = "adminserver";
  private static final String clusterName = "mycluster";
  private static final String msName = "ms-1";
  private String adminServerPodName;
  private String managedServerPrefix;
  private static final String wlSecretName = "weblogic-credentials";
  private static final String encryptionSecretName = "encryptionsecret";
  
  private static NginxParams nginxParams;
  private static String ingressIP = null;
  String adminIngressHost;
  String adminAppIngressHost;
  String clusterIngressHost;
  private final String imageTag1412 = "14.1.2.0.0-jdk17";
  private final String image1412 = BASE_IMAGES_PREFIX + WEBLOGIC_IMAGE_NAME_DEFAULT + ":" + imageTag1412;
  private final String weblogicReady = "/weblogic/ready";
  private final String sampleAppUri = "/sample-war/index.jsp";
  private final String adminAppUri = "/management/tenant-monitoring/servers";
  private final String adminAppText = "RUNNING";
  private final String applicationRuntimes = "/management/weblogic/latest/domainRuntime"
      + "/serverRuntimes/adminserver/applicationRuntimes";
  
  private static LoggingFacade logger = null;

  /**
   * Install Operator.
   * @param namespaces list of namespaces.
   */
  @BeforeAll
  public static void initAll(@Namespaces(9) List<String> ns) {
    logger = getLogger();
    namespaces = ns;

    // get a new unique opNamespace
    logger.info("Assigning unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    // install operator watching 6 domain namespaces
    installAndVerifyOperator(opNamespace, namespaces.subList(1, 9).toArray(String[]::new));

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    namespaces.subList(1, 9).stream().forEach(ImageUtils::createTestRepoSecret);
  }

  /**
   * Shutdown domains created by each test method.
   */
  @AfterEach
  void afterEach() {
    if (listDomainCustomResources(domainNamespace).getItems().stream().anyMatch(dr
        -> dr.getMetadata().getName().equals(domainUid))) {
      DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
      logger.info(Yaml.dump(dcr));
      shutdownDomain(domainUid, domainNamespace);
      logger.info("Checking that adminserver pod {0} does not exist in namespace {1}",
          adminServerPodName, domainNamespace);
      testUntil(
          assertDoesNotThrow(() -> podDoesNotExist(adminServerPodName, domainUid, domainNamespace),
              String.format("podDoesNotExist failed with ApiException for pod %s in namespace %s",
                  adminServerPodName, domainNamespace)),
          logger,
          "pod {0} to be deleted in namespace {1}",
          adminServerPodName,
          domainNamespace);

      for (int i = 1; i <= replicaCount; i++) {
        String managedServerPodName = managedServerPrefix + i;
        testUntil(assertDoesNotThrow(() -> podDoesNotExist(managedServerPodName, domainUid, domainNamespace),
            String.format("podDoesNotExist failed with ApiException for pod %s in namespace %s",
                managedServerPodName, domainNamespace)),
            logger,
            "pod {0} to be deleted in namespace {1}",
            managedServerPodName,
            domainNamespace
        );
      }
    }
  }
  
  /**
   * Test starting a 14.1.2.0.0 domain with serverStartMode(prod).
   * 
   * Verify the sample application is available in default port 7001.
   * Verify the management REST interface is available in default port 7001.
   * Verify the cluster sample application available in default 7101.
   * 
   */
  @Test
  @DisplayName("Test starting a 14.1.2.0.0 domain with serverStartMode as production")
  void testStartModeProduction() throws UnknownHostException, ApiException {
    domainNamespace = namespaces.get(1);
    domainUid = "testdomain1";
    adminServerPodName = domainUid + "-" + adminServerName;
    managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

    createDomain("startmode-prod.yaml");
    dumpResources();

    //name of channel available in domain configuration
    String channelName = "default";
    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName));

    //verify /weblogic/ready and sample app available in port 7001
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http", weblogicReady, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http", sampleAppUri, "HTTP/1.1 200 OK"));
    //verify secure channel is disabled
    assertFalse(verifyServerAccess(domainNamespace, adminServerPodName,
        "7002", "https", weblogicReady, "Connection refused"));

    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = managedServerPrefix + i;
      assertTrue(verifyServerAccess(domainNamespace, managedServerPodName,
          "7101", "http", weblogicReady, "HTTP/1.1 200 OK"));
      assertTrue(verifyServerAccess(domainNamespace, managedServerPodName,
          "7101", "http", sampleAppUri, "HTTP/1.1 200 OK"));
      assertFalse(verifyServerAccess(domainNamespace, managedServerPodName,
          "8101", "https", weblogicReady, "Connection refused"));
    }
  }


  /**
   * Test start secure domain with 14.1.2.0.0 image and ServerStartMode as secure.
   * 
   * Verify all services are available only in HTTPS in adminserver as well as in managed servers.
   * Verify the admin server sample application is available in default SSL port 7002.
   * Verify the management REST interface is available in default admin port 9002.
   * Verify the cluster sample application available in default SSL port 8501.
   * 
   */
  @Test
  @DisplayName("Test start secure domain with 14.1.2.0.0 image and ServerStartMode as secure")
  void testStartModeSecure() throws UnknownHostException, ApiException {
    domainNamespace = namespaces.get(2);
    domainUid = "testdomain2";
    adminServerPodName = domainUid + "-" + adminServerName;
    managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

    createDomain("startmode-secure.yaml");
    dumpResources();

    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName));

    //verify /weblogic/ready and sample app available in port 7001
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "9002", "https", weblogicReady, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7002", "https", sampleAppUri, "HTTP/1.1 200 OK"));
    //verify secure channel is disabled
    assertFalse(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http1", weblogicReady, "Connection refused"));

    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = managedServerPrefix + i;
      assertTrue(verifyServerAccess(domainNamespace, managedServerPodName,
          "710", "http", weblogicReady, "HTTP/1.1 200 OK"));
      assertTrue(verifyServerAccess(domainNamespace, managedServerPodName,
          "7101", "http", sampleAppUri, "HTTP/1.1 200 OK"));
      assertFalse(verifyServerAccess(domainNamespace, managedServerPodName,
          "8101", "https", weblogicReady, "Connection refused"));
    }
  }


  /**
   * Test start secure domain with 14.1.2.0.0 image and ServerStartMode as secure disable SSL at domain level.
   * 
   * Verify all services are available in HTTP, in adminserver as well as in managed servers.
   * Verify the admin server sample application is available in port 7005.
   * Verify the management REST interface is available in default admin port 7001.
   * Verify the cluster sample application available in default port 7101. 
   * 
   */
  @Test
  @DisplayName("Test start secure domain with 14.1.2.0.0 image and ServerStartMode "
      + "as secure disable SSL at domain level")
  void testStartModeSecureOverrideSSL() throws UnknownHostException, ApiException {
    domainNamespace = namespaces.get(3);
    domainUid = "testdomain3";
    adminServerPodName = domainUid + "-" + adminServerName;
    managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

    createDomain("startmode-secure-ssl-override.yaml");
    dumpResources();

    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName)); 
    
    //verify /weblogic/ready and sample app available in port 7001
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "9002", "https", weblogicReady, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7002", "https", sampleAppUri, "HTTP/1.1 200 OK"));
    //verify secure channel is disabled
    assertFalse(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http1", weblogicReady, "Connection refused"));

    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = managedServerPrefix + i;
      assertTrue(verifyServerAccess(domainNamespace, managedServerPodName,
          "710", "http", weblogicReady, "HTTP/1.1 200 OK"));
      assertTrue(verifyServerAccess(domainNamespace, managedServerPodName,
          "7101", "http", sampleAppUri, "HTTP/1.1 200 OK"));
      assertFalse(verifyServerAccess(domainNamespace, managedServerPodName,
          "8101", "https", weblogicReady, "Connection refused"));
    }

    /*    
    //verify /weblogic/ready is available in port 7005
    verifyAppServerAccess(true, getNginxLbNodePort("http"), true, adminIngressHost,
        adminAppUri, adminAppText, true, ingressIP);
    //verify REST access is available in admin server port 7005
    verifyAppServerAccess(true, getNginxLbNodePort("http"), true, adminIngressHost,
        applicationRuntimes, MII_BASIC_APP_NAME, true, ingressIP);
    //verify sample app is available in admin server in secure port 7005
    verifyAppServerAccess(true, getNginxLbNodePort("http"), true, adminAppIngressHost,
        sampleAppUri, adminServerName, true, ingressIP);
    //verify sample application is available in cluster address secure port 7101
    verifyAppServerAccess(true, getNginxLbNodePort("http"), true, clusterIngressHost,
        sampleAppUri, msName, true, ingressIP); */
  }
  
  /**
   * Test starting a 14.1.2.0.0 domain with production and secure mode enabled using MBean configuration.
   * 
   * Verify the sample application is available in default SSL port 7002 in admin server.
   * Verify the management REST interface is available in default port 7002 in admin server.
   * Verify the cluster sample application is available in default SSL port 8101.
   * 
   */
  @Test
  @DisplayName("Test starting a 14.1.2.0.0 domain with production and secure mode enabled using MBean configuration.")
  void testMbeanProductionSecureMBeanConfiguration() throws UnknownHostException, ApiException {
    domainNamespace = namespaces.get(4);
    domainUid = "testdomain4";
    adminServerPodName = domainUid + "-" + adminServerName;
    managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

    createDomain("mbean-prod-secure.yaml");
    dumpResources();

    //name of channel available in domain configuration
    String channelName = "default";
    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName)); 
    
    //verify /weblogic/ready and sample app available in port 7001
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "9002", "https", weblogicReady, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7002", "https", sampleAppUri, "HTTP/1.1 200 OK"));
    //verify secure channel is disabled
    assertFalse(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http1", weblogicReady, "Connection refused"));  
    
    /*
    //verify sample app is available in admin server in port 7002
    verifyAppServerAccess(false, getNginxLbNodePort("https"), true, adminIngressHost,
        sampleAppUri, adminServerName, true, ingressIP);
    //verify admin console is available in port 9002
    verifyAppServerAccess(false, getNginxLbNodePort("https"), true, adminIngressHost,
        adminAppUri, adminAppText, true, ingressIP);
    //verify REST access is available in admin server port 9002
    verifyAppServerAccess(false, getNginxLbNodePort("https"), true, adminIngressHost,
        applicationRuntimes, MII_BASIC_APP_NAME, true, ingressIP);
    //verify sample application is available in cluster address in port 8101
    verifyAppServerAccess(false, getNginxLbNodePort("https"), true, clusterIngressHost,
        sampleAppUri, msName, true, ingressIP);
     */
  }
  
  /**
   * Test start domain with 14.1.2.0.0 image and SSLEnabled at domain level with start mode prod.
   *    
   * Verify the admin server sample application is available in ports 7001 and HTTPS 7002.
   * Verify the management REST interface available in ports 7001 and HTTPS 7002.
   * Verify the cluster sample application available in ports 7101 and HTTPS 8101.
   * 
   */
  @Test
  @DisplayName("Test start domain with 14.1.2.0.0 image and SSLEnabled at domain level with start mode prod.")
  void testStartmodeProductionSSLEnabledGlobal() throws UnknownHostException, ApiException {
    
    domainNamespace = namespaces.get(5);
    domainUid = "testdomain5";
    adminServerPodName = domainUid + "-" + adminServerName;
    managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

    createDomain("mbean-global-ssl-enabled.yaml");
    dumpResources();

    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName)); 
    
    //verify /weblogic/ready and sample app available in port 7001
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "9002", "https", weblogicReady, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7002", "https", sampleAppUri, "HTTP/1.1 200 OK"));
    //verify secure channel is disabled
    assertFalse(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http1", weblogicReady, "Connection refused"));
    
    /*
    //verify /weblogic/ready is available in port 7002
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminIngressHost,
        adminAppUri, adminAppText, true, ingressIP);
    //verify REST access is available in admin server port 7002
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminIngressHost,
        applicationRuntimes, MII_BASIC_APP_NAME, true, ingressIP);
    //verify sample app is available in admin server in secure port 7002
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminAppIngressHost,
        sampleAppUri, adminServerName, true, ingressIP);
    //verify sample application is available in cluster address secure port 8101
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, clusterIngressHost,
        sampleAppUri, msName, true, ingressIP);
     */
  }

  /**
   * Test start domain with 14.1.2.0.0 image, secure mode disabled in MBean, enable SSL at adminserver level.
   * 
   * Verify admin server starts with 2 listen ports non ssl at 7001 and SSL at 7002.
   * Verify the admin server sample application is available in ports 7001 and 7002.
   * Verify the management REST interface available in 7001 and 7002
   * Verify the cluster sample application available in port 8002.
   * 
   */
  @Test
  @DisplayName("Test start domain with 14.1.2.0.0 image, secure mode disabled in MBean, "
      + "enable SSL at adminserver level.")
  void testProductionSSLEnabledPartial() throws UnknownHostException, ApiException {
    domainNamespace = namespaces.get(6);
    domainUid = "testdomain6";
    adminServerPodName = domainUid + "-" + adminServerName;
    managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

    createDomain("mbean-global-ssl-disbled-partial.yaml");
    dumpResources();

    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName)); 
    
    //verify /weblogic/ready is available in port 7001 and 7002
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http", weblogicReady, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7002", "https", weblogicReady, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http", sampleAppUri, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7002", "https", sampleAppUri, "HTTP/1.1 200 OK"));

    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPrefix = domainUid + "-" + clusterName + "-ms-";
      String managedServerPodName = managedServerPrefix + i;
      assertTrue(verifyServerAccess(domainNamespace, managedServerPodName,
          "8002", "https", sampleAppUri, "HTTP/1.1 200 OK"));
    }
  }

  /**
   * Test start domain with 14.1.2.0.0 image, secure mode enabled in MBean, disable SSL, 
   * enable listenport at domain level.
   * 
   * Verify admin server starts with 2 listen ports non ssl at 7001 and SSL at 7002.
   * Verify the admin server sample application is available in ports 7001 and 7002.
   * Verify the management REST interface available in 7001 and 7002
   * Verify the cluster sample application available in port 8002.
   * 
   */
  @Test
  @DisplayName("Test start domain with 14.1.2.0.0 image, secure mode disabled in MBean, "
      + "enable SSL at adminserver level.")
  void testSecureSSLDisabledListenportEnabled() throws UnknownHostException, ApiException {
    domainNamespace = namespaces.get(7);
    domainUid = "testdomain6";
    adminServerPodName = domainUid + "-" + adminServerName;
    managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

    createDomain("secure-listenport-enabled.yaml");
    dumpResources();

    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName));    
    
    //verify /weblogic/ready is available in port 7001 and 7002
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "9002", "https", weblogicReady, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http", sampleAppUri, "HTTP/1.1 200 OK"));

    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPrefix = domainUid + "-" + clusterName + "-ms-";
      String managedServerPodName = managedServerPrefix + i;
      assertTrue(verifyServerAccess(domainNamespace, managedServerPodName,
          "7101", "https", sampleAppUri, "HTTP/1.1 200 OK"));
    }
  }
  
  /**
   * Test start domain with 14.1.2.0.0 image, secure mode enabled in MBean, disable SSL, 
   * enable listenport at domain level.
   * 
   * Verify admin server starts with 2 listen ports non ssl at 7001 and SSL at 7002.
   * Verify the admin server sample application is available in ports 7001 and 7002.
   * Verify the management REST interface available in 7001 and 7002
   * Verify the cluster sample application available in port 8002.
   * 
   */
  @Test
  @DisplayName("Test start domain with 14.1.2.0.0 image, secure mode disabled in MBean, "
      + "enable SSL at adminserver level.")
  void testStartSecureSSLDisabledListenportEnabled() throws UnknownHostException, ApiException {
    domainNamespace = namespaces.get(8);
    domainUid = "testdomain6";
    adminServerPodName = domainUid + "-" + adminServerName;
    managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

    createDomain("startsecure-listenport-enabled.yaml");
    dumpResources();

    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName));  
    
    //verify /weblogic/ready is available in port 7001 and 7002
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "9002", "https", weblogicReady, "HTTP/1.1 200 OK"));
    assertTrue(verifyServerAccess(domainNamespace, adminServerPodName,
        "7001", "http", sampleAppUri, "HTTP/1.1 200 OK"));

    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPrefix = domainUid + "-" + clusterName + "-ms-";
      String managedServerPodName = managedServerPrefix + i;
      assertTrue(verifyServerAccess(domainNamespace, managedServerPodName,
          "7101", "https", sampleAppUri, "HTTP/1.1 200 OK"));
    }
  }
  
  private DomainResource createDomain(String wdtModel) {
    // create WDT properties file for the WDT model
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.CREATE);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-securedomain-image";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", wdtModel);

    // create auxiliary domain creation image
    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());

    //create a MII domain resource with the auxiliary image
    DomainResource domain = createDomainUsingAuxiliaryImage(domainNamespace, domainUid, image1412, auxImage, null);
    return domain;
  }
  
  private void dumpResources() throws ApiException {
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    logger.info(Yaml.dump(getPod(domainNamespace, null, adminServerPodName)));
    logger.info(Yaml.dump(getPod(domainNamespace, null, domainUid + "-" + clusterName + "-ms-1")));
  }
    
  
  /**
   * Create domain custom resource with auxiliary image, base image and channel name.
   *
   * @param domainNamespace namespace in which to create domain
   * @param domainUid domain id
   * @param baseImage base image used by the WebLogic pods
   * @param auxImage auxiliary image containing domain creation WDT model and properties files
   * @param channelName name of the channel to configure in domain resource
   * @return domain resource object
   */
  private DomainResource createDomainUsingAuxiliaryImage(String domainNamespace, String domainUid,
      String baseImage, String auxImage, String channelName) {
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
    // replace the default channel with given channel from method parameters
    if (channelName != null) {
      Channel channel = domainCR.getSpec().getAdminServer().getAdminService().channels().get(0);
      channel.channelName(channelName);
      domainCR.getSpec().adminServer().adminService().channels(List.of(channel));
    }
    //add SSL properties
    domainCR.getSpec().getServerPod()
        .addEnvItem(new V1EnvVar()
            .name("JAVA_OPTIONS")
            .value(SSL_PROPERTIES))
        .addEnvItem(new V1EnvVar()
            .name("WLSDEPLOY_PROPERTIES")
            .value(SSL_PROPERTIES));

    // create domain and verify its running
    logger.info("Creating domain {0} with auxiliary images {1} in namespace {2}",
        domainUid, auxImage, domainNamespace);
    createDomainAndVerify(domainUid, domainCR, domainNamespace,
        adminServerPodName, managedServerPrefix, replicaCount);

    return domainCR;
  }

  /**
   * Create auxiliary image.
   *
   * @param imageName name of the auxiliary image
   * @param imageTag auxiliary image tag
   * @param wdtModelFile WDT model file
   * @param wdtVariableFile WDT property file
   * @return name of the auxiliary image created
   */
  private String createAuxImage(String imageName, String imageTag, String wdtModelFile, String wdtVariableFile) {
    // build sample-app application
    AppParams appParams = defaultAppParams()
        .srcDirList(Collections.singletonList(MII_BASIC_APP_NAME))
        .appArchiveDir(ARCHIVE_DIR + this.getClass().getSimpleName())
        .appName(MII_BASIC_APP_NAME);
    assertTrue(buildAppArchive(appParams),
        String.format("Failed to create app archive for %s", MII_BASIC_APP_NAME));
    List<String> archiveList = Collections.singletonList(appParams.appArchiveDir() + "/" + MII_BASIC_APP_NAME + ".zip");

    //create an auxilary image with model and sample-app application
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


  private void verifyChannel(String domainNamespace, String domainUid, List<String> channelNames) {
    //get the number of channels available in domain resource and assert it is equal to the expected count
    assertThat(assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace)
        .getSpec().getAdminServer().getAdminService().getChannels().stream().count())
        .equals(Long.valueOf(channelNames.size())))
        .withFailMessage("Number of channels are not equal to expected length");

    //verify the name of channels available in the domain resource match with the expected names
    for (String channelName : channelNames) {
      assertThat(assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace)
          .getSpec().getAdminServer().getAdminService().getChannels().stream()
          .anyMatch(ch -> ch.channelName().equals(channelName))))
          .as(String.format("Channel %s was found in domain resource %s", channelName, domainUid))
          .withFailMessage(String.format("Channel %s was found not in domain resource %s", channelName, domainUid))
          .isEqualTo(true);
    }
  }

  private static boolean verifyServerAccess(String namespace, String podName, String port,
      String protocol, String uri, String expected) {
    logger.info("Checking the server access");
    String command = KUBERNETES_CLI + " exec -n " + namespace + "  " + podName
        + " -- curl -vkgs --noproxy '*' " + protocol + "://" + podName + ":" + port + uri;
    ExecResult result = null;
    try {
      result = ExecCommand.exec(command, true);
    } catch (IOException | InterruptedException ex) {
      logger.severe(ex.getMessage());
    }
    assertNotNull(result, "result is null");
    String response = result.stdout().trim();
    logger.info(response);
    logger.info(result.stderr());
    logger.info("{0}", result.exitValue());
    return response.contains(expected);
  }
  
}
