// Copyright (c) 2023, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1HTTPIngressPath;
import io.kubernetes.client.openapi.models.V1HTTPIngressRuleValue;
import io.kubernetes.client.openapi.models.V1IngressBackend;
import io.kubernetes.client.openapi.models.V1IngressRule;
import io.kubernetes.client.openapi.models.V1IngressServiceBackend;
import io.kubernetes.client.openapi.models.V1IngressTLS;
import io.kubernetes.client.openapi.models.V1ServiceBackendPort;
import io.kubernetes.client.util.Yaml;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.DomainResource;
import oracle.weblogic.kubernetes.actions.impl.AppParams;
import oracle.weblogic.kubernetes.actions.impl.NginxParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.WitParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.DomainUtils;
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
import static oracle.weblogic.kubernetes.TestConstants.IT_ITMIIDOMAINUPGRADETOSECUREMODE_HTTPS_HOSTPORT;
import static oracle.weblogic.kubernetes.TestConstants.IT_ITMIIDOMAINUPGRADETOSECUREMODE_HTTPS_NODEPORT;
import static oracle.weblogic.kubernetes.TestConstants.IT_ITMIIDOMAINUPGRADETOSECUREMODE_HTTP_HOSTPORT;
import static oracle.weblogic.kubernetes.TestConstants.IT_ITMIIDOMAINUPGRADETOSECUREMODE_HTTP_NODEPORT;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.KUBERNETES_CLI;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_NAME;
import static oracle.weblogic.kubernetes.TestConstants.OKE_CLUSTER_PRIVATEIP;
import static oracle.weblogic.kubernetes.TestConstants.RESULTS_TEMPFILE_DIR;
import static oracle.weblogic.kubernetes.TestConstants.SSL_PROPERTIES;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_NAME_DEFAULT;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ARCHIVE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.buildAppArchive;
import static oracle.weblogic.kubernetes.actions.TestActions.createIngress;
import static oracle.weblogic.kubernetes.actions.TestActions.defaultAppParams;
import static oracle.weblogic.kubernetes.actions.TestActions.getDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.getPod;
import static oracle.weblogic.kubernetes.actions.TestActions.getPodCreationTimestamp;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.listDomainCustomResources;
import static oracle.weblogic.kubernetes.actions.TestActions.listIngresses;
import static oracle.weblogic.kubernetes.actions.TestActions.now;
import static oracle.weblogic.kubernetes.actions.TestActions.shutdownDomain;
import static oracle.weblogic.kubernetes.actions.impl.Domain.patchDomainCustomResource;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.podDoesNotExist;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.verifyRollingRestartOccurred;
import static oracle.weblogic.kubernetes.utils.AuxiliaryImageUtils.createAndPushAuxiliaryImage;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createDomainResourceWithAuxiliaryImage;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.formatIPv6Host;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getDateAndTimeStamp;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getServiceExtIPAddrtOke;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.LoadBalancerUtils.installAndVerifyNginx;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithTLSCertKey;
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
    domainNamespace = namespaces.get(2);
    domainUid = "testdomain1";
    adminServerPodName = domainUid + "-" + adminServerName;
    String managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

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
    String managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

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
    domainNamespace = namespaces.get(4);
    domainUid = "testdomain6";
    adminServerPodName = domainUid + "-" + adminServerName;
    // create WDT properties file for the WDT model
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.CREATE);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-startmodesecure";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "startmode-secure-ssl-override.yaml");

    // create auxiliary domain creation image
    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = BASE_IMAGES_PREFIX + WEBLOGIC_IMAGE_NAME_DEFAULT + ":" + imageTag1412;
    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //create a MII domain resource with the auxiliary image
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, channelName);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    logger.info(Yaml.dump(getPod(domainNamespace, null, adminServerPodName)));
    logger.info(Yaml.dump(getPod(domainNamespace, null, domainUid + "-" + clusterName + "-ms-1")));
    //create ingress resources to route traffic to various service endpoints
    createNginxIngressHostRouting(domainUid, 7005, 7005, 7101, nginxParams.getIngressClassName(), false);

    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName));

    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    //get ingress ip of the ingress controller to send http requests to servers in domain
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;

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
        sampleAppUri, msName, true, ingressIP);
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
    domainNamespace = namespaces.get(3);
    domainUid = "testdomain3";
    adminServerPodName = domainUid + "-" + adminServerName;
    // create WDT properties file for the WDT model
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.CREATE);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-mbeanprodsecuressldisabled";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "mbean-prod-secure.yaml");

    // create auxiliary domain creation image
    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = BASE_IMAGES_PREFIX + WEBLOGIC_IMAGE_NAME_DEFAULT + ":" + imageTag1412;
    //name of channel available in domain configuration
    String channelName = "default";
    //create a MII domain resource with the auxiliary image
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, null);
    //create ingress resources to route traffic to various service endpoints
    createNginxIngressHostRouting(domainUid, 9002, 7002, 8101, nginxParams.getIngressClassName(), false);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    logger.info(Yaml.dump(getPod(domainNamespace, null, adminServerPodName)));
    logger.info(Yaml.dump(getPod(domainNamespace, null, domainUid + "-" + clusterName + "-ms-1")));
    
    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName));
    
    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    //get ingress ip of the ingress controller to send http requests to servers in domain
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;

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
    domainNamespace = namespaces.get(4);
    domainUid = "testdomain4";
    adminServerPodName = domainUid + "-" + adminServerName;
    // create WDT properties file for the WDT model
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.CREATE);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-sslenabledglobal";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "mbean-global-ssl-enabled.yaml");

    // create auxiliary domain creation image
    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = BASE_IMAGES_PREFIX + WEBLOGIC_IMAGE_NAME_DEFAULT + ":" + imageTag1412;
    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //create a MII domain resource with the auxiliary image
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, channelName);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    logger.info(Yaml.dump(getPod(domainNamespace, null, adminServerPodName)));
    logger.info(Yaml.dump(getPod(domainNamespace, null, domainUid + "-" + clusterName + "-ms-1")));
    //create ingress resources to route traffic to various service endpoints
    createNginxIngressHostRouting(domainUid, 7001, 7002, 8101, nginxParams.getIngressClassName(), true);

    //verify the number of channels available in the domain resource match with the count and name
    verifyChannel(domainNamespace, domainUid, List.of(channelName));

    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    //get ingress ip of the ingress controller to send http requests to servers in domain
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;

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
    domainNamespace = namespaces.get(4);
    domainUid = "testdomain4";
    adminServerPodName = domainUid + "-" + adminServerName;
    // create WDT properties file for the WDT model
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.CREATE);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-ssldisbledglobalpartial";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "mbean-global-ssl-disbled-partial.yaml");

    // create auxiliary domain creation image
    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = BASE_IMAGES_PREFIX + WEBLOGIC_IMAGE_NAME_DEFAULT + ":" + imageTag1412;
    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //create a MII domain resource with the auxiliary image
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, channelName);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    logger.info(Yaml.dump(getPod(domainNamespace, null, adminServerPodName)));
    logger.info(Yaml.dump(getPod(domainNamespace, null, domainUid + "-" + clusterName + "-ms-1")));

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
    domainNamespace = namespaces.get(5);
    domainUid = "testdomain7";
    adminServerPodName = domainUid + "-" + adminServerName;
    // create WDT properties file for the WDT model
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.CREATE);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-ssldisablelistenportenable";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "secure-listenport-enabled.yaml");

    // create auxiliary domain creation image
    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = BASE_IMAGES_PREFIX + WEBLOGIC_IMAGE_NAME_DEFAULT + ":" + imageTag1412;
    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //create a MII domain resource with the auxiliary image
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, channelName);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    logger.info(Yaml.dump(getPod(domainNamespace, null, adminServerPodName)));
    logger.info(Yaml.dump(getPod(domainNamespace, null, domainUid + "-" + clusterName + "-ms-1")));

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
    domainNamespace = namespaces.get(5);
    domainUid = "testdomain7";
    adminServerPodName = domainUid + "-" + adminServerName;
    // create WDT properties file for the WDT model
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.CREATE);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-ssldisablelistenportenable";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "startsecure-listenport-enabled.yaml");

    // create auxiliary domain creation image
    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = BASE_IMAGES_PREFIX + WEBLOGIC_IMAGE_NAME_DEFAULT + ":" + imageTag1412;
    //name of channel available in domain configuration
    String channelName = "internal-admin";
    //create a MII domain resource with the auxiliary image
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, channelName);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    logger.info(Yaml.dump(getPod(domainNamespace, null, adminServerPodName)));
    logger.info(Yaml.dump(getPod(domainNamespace, null, domainUid + "-" + clusterName + "-ms-1")));

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
    String adminServerPodName = domainUid + "-" + adminServerName;
    String managedServerPrefix = domainUid + "-" + clusterName + "-ms-";

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

  /**
   * Upgrade domain with 1412 images using domain patching and verify rolling restart.
   *
   * @param domainNamespace domain namespace
   * @param domainUid domain id
   * @param newImage the new 1412 image to patch the domain resource
   */
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
    adminServerPodName = domainUid + "-adminserver";
    managedServerPrefix = domainUid + "-mycluster-ms-";

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

  private static void installNginx() {
    // install and verify Nginx ingress controller
    logger.info("Installing Nginx controller using helm");
    nginxParams = installAndVerifyNginx(ingressNamespace, 
        IT_ITMIIDOMAINUPGRADETOSECUREMODE_HTTP_NODEPORT, 
        IT_ITMIIDOMAINUPGRADETOSECUREMODE_HTTPS_NODEPORT);
  }

  /**
   * Create Ingress objects for routing administration and application traffic to admin server and cluster.
   *
   * @param domainUid domain id for which to create the traffic rules
   * @param adminPort administration port of the admin server
   * @param adminSecureAppPort secure application of admin server
   * @param msPort managed server port
   * @param ingressClassName ingress class name
   * @param isTLS is TLS needs to configured
   */
  private void createNginxIngressHostRouting(String domainUid, int adminPort, int adminSecureAppPort, int msPort,
      String ingressClassName, boolean isTLS) {
    // create an ingress in domain namespace
    String ingressName;

    if (isTLS) {
      ingressName = domainUid + "-nginx-tls";
    } else {
      ingressName = domainUid + "-nginx-nontls";
    }

    // create ingress rules
    List<V1IngressRule> ingressRules = new ArrayList<>();
    List<V1IngressTLS> tlsList = new ArrayList<>();

    //cluster rules
    V1HTTPIngressPath clusterIngressPath = new V1HTTPIngressPath()
        .path(null)
        .pathType("ImplementationSpecific")
        .backend(new V1IngressBackend()
            .service(new V1IngressServiceBackend()
                .name(domainUid + "-cluster-mycluster")
                .port(new V1ServiceBackendPort()
                    .number(msPort)))
        );
    //admin port ingress rules
    V1HTTPIngressPath adminIngressPath = new V1HTTPIngressPath()
        .path(null)
        .pathType("ImplementationSpecific")
        .backend(new V1IngressBackend()
            .service(new V1IngressServiceBackend()
                .name(domainUid + "-adminserver")
                .port(new V1ServiceBackendPort()
                    .number(adminPort)))
        );
    //admin server application ingress rules
    V1HTTPIngressPath adminAppIngressPath = new V1HTTPIngressPath()
        .path(null)
        .pathType("ImplementationSpecific")
        .backend(new V1IngressBackend()
            .service(new V1IngressServiceBackend()
                .name(domainUid + "-adminserver")
                .port(new V1ServiceBackendPort()
                    .number(adminSecureAppPort)))
        );

    // set the ingress rule host based in TLS is enabled or not
    if (isTLS) {
      adminIngressHost = domainUid + "." + domainNamespace + ".admin.ssl.test";
      adminAppIngressHost = domainUid + "." + domainNamespace + ".adminapp.ssl.test";
      clusterIngressHost = domainUid + "." + domainNamespace + ".cluster.ssl.test";
    } else {
      adminIngressHost = domainUid + "." + domainNamespace + ".admin.nonssl.test";
      adminAppIngressHost = domainUid + "." + domainNamespace + ".adminapp.nonssl.test";
      clusterIngressHost = domainUid + "." + domainNamespace + ".cluster.nonssl.test";
    }
    V1IngressRule adminIngressRule = new V1IngressRule()
        .host(adminIngressHost)
        .http(new V1HTTPIngressRuleValue()
            .paths(Collections.singletonList(adminIngressPath)));
    V1IngressRule adminAppIngressRule = new V1IngressRule()
        .host(adminAppIngressHost)
        .http(new V1HTTPIngressRuleValue()
            .paths(Collections.singletonList(adminAppIngressPath)));    
    V1IngressRule clusterIngressRule = new V1IngressRule()
        .host(clusterIngressHost)
        .http(new V1HTTPIngressRuleValue()
            .paths(Collections.singletonList(clusterIngressPath)));

    ingressRules.add(adminIngressRule);
    ingressRules.add(adminAppIngressRule);
    ingressRules.add(clusterIngressRule);

    //create the necessary certificates if TLS is enabled to decrypt data at ingress controller
    if (isTLS) {
      String admintlsSecretName = domainUid + "-admin-nginx-tls-secret";
      String clustertlsSecretName = domainUid + "-cluster-nginx-tls-secret";
      createCertKeyFiles(adminIngressHost);
      assertDoesNotThrow(() -> createSecretWithTLSCertKey(admintlsSecretName,
          domainNamespace, tlsKeyFile, tlsCertFile));
      createCertKeyFiles(clusterIngressHost);
      assertDoesNotThrow(() -> createSecretWithTLSCertKey(clustertlsSecretName,
          domainNamespace, tlsKeyFile, tlsCertFile));
      V1IngressTLS admintls = new V1IngressTLS()
          .addHostsItem(adminIngressHost)
          .secretName(admintlsSecretName);
      V1IngressTLS adminApptls = new V1IngressTLS()
          .addHostsItem(adminAppIngressHost)
          .secretName(admintlsSecretName);      
      V1IngressTLS clustertls = new V1IngressTLS()
          .addHostsItem(clusterIngressHost)
          .secretName(clustertlsSecretName);
      tlsList.add(admintls);
      tlsList.add(adminApptls);
      tlsList.add(clustertls);
    }
    //add the annotation to send the request from ingress controller to backend services through HTTPS
    assertDoesNotThrow(() -> {
      Map<String, String> annotations = null;
      if (isTLS) {
        annotations = new HashMap<>();
        annotations.put("nginx.ingress.kubernetes.io/backend-protocol", "HTTPS");
      }
      createIngress(ingressName, domainNamespace, annotations, ingressClassName,
          ingressRules, (isTLS ? tlsList : null));
    });

    assertDoesNotThrow(() -> {
      List<String> ingresses = listIngresses(domainNamespace);
      logger.info(ingresses.toString());
    });
    // check the ingress was found in the domain namespace
    assertThat(assertDoesNotThrow(() -> listIngresses(domainNamespace)))
        .as(String.format("Test ingress %s was found in namespace %s", ingressName, domainNamespace))
        .withFailMessage(String.format("Ingress %s was not found in namespace %s", ingressName, domainNamespace))
        .contains(ingressName);
    logger.info("ingress {0} was created in namespace {1}", ingressName, domainNamespace);
  }

  /**
   *
   * Create ingress resource to route administration requests to admin server.
   *
   * @param domainUid domain id
   * @param adminPort adminport of admin server
   * @param ingressClassName ingress class name
   * @param isTLS is TLS connection
   * @return name of the hostname configued in the routing
   */
  private String createAdministrationIngressHostRouting(String domainUid, int adminPort, 
      String ingressClassName, boolean isTLS) {
    // create an ingress in domain namespace
    String ingressName;
    String adminIngressHost;

    if (isTLS) {
      ingressName = domainUid + "-administration-tls";
    } else {
      ingressName = domainUid + "-administration-nontls";
    }

    // create ingress rules for two domains
    List<V1IngressRule> ingressRules = new ArrayList<>();
    List<V1IngressTLS> tlsList = new ArrayList<>();
    V1HTTPIngressPath adminIngressPath = new V1HTTPIngressPath()
        .path(null)
        .pathType("ImplementationSpecific")
        .backend(new V1IngressBackend()
            .service(new V1IngressServiceBackend()
                .name(domainUid + "-adminserver")
                .port(new V1ServiceBackendPort()
                    .number(adminPort)))
        );

    // set the ingress rule host
    if (isTLS) {
      adminIngressHost = domainUid + "." + domainNamespace + ".administration.ssl.test";
    } else {
      adminIngressHost = domainUid + "." + domainNamespace + ".administration.nonssl.test";
    }
    V1IngressRule adminIngressRule = new V1IngressRule()
        .host(adminIngressHost)
        .http(new V1HTTPIngressRuleValue()
            .paths(Collections.singletonList(adminIngressPath)));

    ingressRules.add(adminIngressRule);

    if (isTLS) {
      String admintlsSecretName = domainUid + "-administration-nginx-tls-secret";
      createCertKeyFiles(adminIngressHost);
      assertDoesNotThrow(() -> createSecretWithTLSCertKey(admintlsSecretName,
          domainNamespace, tlsKeyFile, tlsCertFile));
      createCertKeyFiles(clusterIngressHost);
      V1IngressTLS admintls = new V1IngressTLS()
          .addHostsItem(adminIngressHost)
          .secretName(admintlsSecretName);
      tlsList.add(admintls);
    }
    assertDoesNotThrow(() -> {
      Map<String, String> annotations = null;
      if (isTLS) {
        annotations = new HashMap<>();
        annotations.put("nginx.ingress.kubernetes.io/backend-protocol", "HTTPS");
      }
      createIngress(ingressName, domainNamespace, annotations, ingressClassName,
          ingressRules, (isTLS ? tlsList : null));
    });

    assertDoesNotThrow(() -> {
      List<String> ingresses = listIngresses(domainNamespace);
      logger.info(ingresses.toString());
    });
    // check the ingress was found in the domain namespace
    assertThat(assertDoesNotThrow(() -> listIngresses(domainNamespace)))
        .as(String.format("Test ingress %s was found in namespace %s", ingressName, domainNamespace))
        .withFailMessage(String.format("Ingress %s was not found in namespace %s", ingressName, domainNamespace))
        .contains(ingressName);

    logger.info("ingress {0} was created in namespace {1}", ingressName, domainNamespace);
    return adminIngressHost;
  }
  
  private static Path tlsCertFile;
  private static Path tlsKeyFile;

  private static void createCertKeyFiles(String cn) {
    assertDoesNotThrow(() -> {
      tlsKeyFile = Files.createTempFile(RESULTS_TEMPFILE_DIR, "tls", ".key");
      tlsCertFile = Files.createTempFile(RESULTS_TEMPFILE_DIR, "tls", ".crt");
      String command = "openssl req -x509 -nodes -days 365 -newkey rsa:2048 -keyout " + tlsKeyFile
          + " -out " + tlsCertFile + " -subj \"/CN=" + cn + "\"";
      logger.info("Executing command: {0}", command);
      ExecCommand.exec(command, true);
    });
  }

  private static int getNginxLbNodePort(String channelName) {
    String nginxServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    if (TestConstants.KIND_CLUSTER
        && !TestConstants.WLSIMG_BUILDER.equals(TestConstants.WLSIMG_BUILDER_DEFAULT)) {
      if (channelName.equals("https")) {
        return IT_ITMIIDOMAINUPGRADETOSECUREMODE_HTTPS_HOSTPORT;
      } else {
        return IT_ITMIIDOMAINUPGRADETOSECUREMODE_HTTP_HOSTPORT;
      }
    }
    return getServiceNodePort(ingressNamespace, nginxServiceName, channelName);
  }

  private void verifyAppServerAccess(boolean isTLS,
      int lbNodePort,
      boolean isHostRouting,
      String ingressHostName,
      String pathLocation,
      String content,
      boolean useCredentials,
      String... hostName) throws UnknownHostException {

    StringBuffer url = new StringBuffer();
    String hostAndPort;
    if (hostName != null && hostName.length > 0) {
      hostAndPort = OKE_CLUSTER_PRIVATEIP ? hostName[0] : hostName[0] + ":" + lbNodePort;
    } else {
      String host = formatIPv6Host(K8S_NODEPORT_HOST);
      hostAndPort = host + ":" + lbNodePort;
    }
    if (TestConstants.KIND_CLUSTER
        && !TestConstants.WLSIMG_BUILDER.equals(TestConstants.WLSIMG_BUILDER_DEFAULT)) {
      hostAndPort = formatIPv6Host(InetAddress.getLocalHost().getHostAddress()) + ":" + lbNodePort;
    }

    if (isTLS) {
      url.append("https://");
    } else {
      url.append("http://");
    }
    url.append(hostAndPort);
    url.append(pathLocation);

    String credentials = "";
    if (useCredentials) {
      credentials = "--user " + ADMIN_USERNAME_DEFAULT + ":" + ADMIN_PASSWORD_DEFAULT;
    }
    String curlCmd;
    if (isHostRouting) {
      curlCmd = String.format("curl -g -ks --show-error --noproxy '*' "
          + credentials + " -H 'host: %s' %s", ingressHostName, url.toString());
    } else {
      if (isTLS) {
        curlCmd = String.format("curl -g -ks --show-error --noproxy '*' "
            + credentials + " -H 'WL-Proxy-Client-IP: 1.2.3.4' -H 'WL-Proxy-SSL: false' %s", url.toString());
      } else {
        curlCmd = String.format("curl -g -ks --show-error --noproxy '*' " + credentials + " %s", url.toString());
      }
    }

    boolean urlAccessible = false;
    for (int i = 0; i < 10; i++) {
      assertDoesNotThrow(() -> TimeUnit.SECONDS.sleep(1));
      ExecResult result;
      try {
        getLogger().info("Accessing url with curl request, iteration {0}: {1}", i, curlCmd);
        result = ExecCommand.exec(curlCmd, true);
        String response = result.stdout().trim();
        getLogger().info("exitCode: {0}, \nstdout: {1}, \nstderr: {2}",
            result.exitValue(), response, result.stderr());
        if (response.contains(content)) {
          urlAccessible = true;
          break;
        }
      } catch (IOException | InterruptedException ex) {
        getLogger().severe(ex.getMessage());
      }
    }
    assertTrue(urlAccessible, "Couldn't access server url");
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
