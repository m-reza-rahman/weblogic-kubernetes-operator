// Copyright (c) 2020, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.io.IOException;
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
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_IMAGES_PREFIX;
import static oracle.weblogic.kubernetes.TestConstants.ENCRYPION_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ENCRYPION_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_NAME;
import static oracle.weblogic.kubernetes.TestConstants.OKE_CLUSTER;
import static oracle.weblogic.kubernetes.TestConstants.OKE_CLUSTER_PRIVATEIP;
import static oracle.weblogic.kubernetes.TestConstants.SSL_PROPERTIES;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ARCHIVE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.buildAppArchive;
import static oracle.weblogic.kubernetes.actions.TestActions.createIngress;
import static oracle.weblogic.kubernetes.actions.TestActions.defaultAppParams;
import static oracle.weblogic.kubernetes.actions.TestActions.getDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.getPodCreationTimestamp;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.listDomainCustomResources;
import static oracle.weblogic.kubernetes.actions.TestActions.listIngresses;
import static oracle.weblogic.kubernetes.actions.TestActions.now;
import static oracle.weblogic.kubernetes.actions.TestActions.shutdownDomain;
import static oracle.weblogic.kubernetes.actions.impl.Domain.patchDomainCustomResource;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.verifyRollingRestartOccurred;
import static oracle.weblogic.kubernetes.utils.AuxiliaryImageUtils.createAndPushAuxiliaryImage;
import static oracle.weblogic.kubernetes.utils.CommonLBTestUtils.checkIngressReady;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createDomainResourceWithAuxiliaryImage;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getDateAndTimeStamp;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getServiceExtIPAddrtOke;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.LoadBalancerUtils.installAndVerifyNginx;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithTLSCertKey;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretsForImageRepos;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

  private static List<String> namespaces;
  private static String opNamespace;
  private static String ingressNamespace;  
  private static String domainNamespace;
  private static int replicaCount = 1;
  private static String domainUid;
  private static final String adminServerName = "adminserver";
  private static final String configMapName = "default-admin-configmap";
  private String adminServerPodName;
  private final String managedServerPrefix = domainUid + "mycluster-ms-";

  private static Path pathToEnableSSLYaml;
  private static LoggingFacade logger = null;
  private static String adminSvcSslPortExtHost = null;
  
  private static final String clusterName = "mycluster";
  private static final String wlSecretName = "weblogic-credentials";
  private static final String encryptionSecretName = "encryptionsecret";
  
  private static NginxParams nginxParams;
  private static String ingressIP = null;
  String adminIngressHost;
  String adminAppIngressHost;
  String clusterIngressHost;
  private final String wlsConsoleText = "WebLogic Administration Console";  
  private final String applicationRuntimes = "/management/weblogic/latest/domainRuntime"
      + "/serverRuntimes/adminserver/applicationRuntimes";

  /**
   * Install Operator.
   * Create domain resource.
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(8) List<String> ns) {
    logger = getLogger();
    namespaces = ns;

    // get a new unique opNamespace
    logger.info("Assigning unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);
    // get a new unique opNamespace for ingress
    logger.info("Assigning unique namespace for Ingress");
    ingressNamespace = namespaces.get(1);

    // install and verify operator
    installAndVerifyOperator(opNamespace, namespaces.subList(2, 8).toArray(String[]::new));

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    namespaces.subList(2, 8).stream().forEach(ImageUtils::createTestRepoSecret);

    // install Nginx ingress controller for all test cases using Nginx
    installNginx();
    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;   
  }

  /**
   * Shutdown domains created by each test method.
   */
  @AfterEach
  void afterEach() {
    if (listDomainCustomResources(domainNamespace).getItems().stream().anyMatch(dr
        -> dr.getMetadata().getName().equals(domainUid))) {
      shutdownDomain(domainUid, domainNamespace);
    }
  }
  
  /**
   * Test upgrade from 1411 to 1412 with production off and secure mode off.
   * 
   * Verify the sample application and console are available in default port 7001 before upgrade.
   * Verify the management REST interface continue to be available in default port 7001 before and after upgrade.
   * Verify the sample application continue to available in default port 7001 after upgrade.
   * Verify the console is moved to a new location in 1412.
   * 
   */
  @Test
  @DisplayName("Test upgrade from 1411 to 1412 with production off and secure mode off")
  void testUpgrade1411to1412ProdOff() {
    //no changes
    domainNamespace = namespaces.get(2);
    domainUid = "testdomain1";
    adminServerPodName = domainUid + "-" + adminServerName;
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "SSLEnabled=false\n", StandardOpenOption.CREATE);
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "ProductionModeEnabled=false\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "SecureModeEnabled=false\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "AdministrationPortEnabled=false\n", StandardOpenOption.APPEND);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-securemodeoff";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "upgrade-model.yaml");

    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = WEBLOGIC_IMAGE_NAME + ":" + "14.1.1.0-11";
    String channelName = "default";
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, null);
    createNginxIngressHostRouting(domainUid, 7001, 7002, 8001, nginxParams.getIngressClassName(), false);
    
    verifyChannel(domainNamespace, domainUid, List.of(channelName));
    
    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;

    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);    
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", wlsConsoleText, false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        applicationRuntimes, MII_BASIC_APP_NAME, true, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);

    String image1412 = "wls-docker-dev-local.dockerhub-phx.oci.oraclecorp.com/weblogic:14.1.2.0.0";
    upgradeImage(domainNamespace, domainUid, image1412);
    verifyChannel(domainNamespace, domainUid, List.of(channelName));
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", "This document you requested has moved", false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        applicationRuntimes, MII_BASIC_APP_NAME, true, ingressIP);    
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);
  }
  
  /**
   * Test upgrade from 1411 to 1412 with production on and secure mode off.
   * 
   * Verify the sample application and console are available in default port 7001 before upgrade.
   * Verify the management REST interface continue to be available in default port 7001 before and after upgrade.
   * Verify the sample application continue to available in default port 7001 after upgrade.
   * Verify the console is moved to a new location in 1412.
   * 
   */
  @Test
  @DisplayName("Test upgrade from 1411 to 1412 with production on and secure mode off")
  void testUpgrade1411to1412ProdOnSecOff() {
    //no changes
    domainNamespace = namespaces.get(3);
    domainUid = "testdomain2";
    adminServerPodName = domainUid + "-" + adminServerName;
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "SSLEnabled=false\n", StandardOpenOption.CREATE);
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "ProductionModeEnabled=true\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "SecureModeEnabled=false\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "AdministrationPortEnabled=false\n", StandardOpenOption.APPEND);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-prodon";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "upgrade-model.yaml");

    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = WEBLOGIC_IMAGE_NAME + ":" + "14.1.1.0-11";
    String channelName = "default";
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, null);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    createNginxIngressHostRouting(domainUid, 7001, 7002, 8001, nginxParams.getIngressClassName(), false);

    verifyChannel(domainNamespace, domainUid, List.of(channelName));

    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;

    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);    
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", wlsConsoleText, false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        applicationRuntimes, MII_BASIC_APP_NAME, true, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);

    String image1412 = "wls-docker-dev-local.dockerhub-phx.oci.oraclecorp.com/weblogic:14.1.2.0.0";
    upgradeImage(domainNamespace, domainUid, image1412);
    verifyChannel(domainNamespace, domainUid, List.of(channelName));
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", "This document you requested has moved", false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        applicationRuntimes, MII_BASIC_APP_NAME, true, ingressIP);    
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);
  }

  /**
   * Test upgrade from 1411 to 1412 with production on and secure mode on.
   */
  @Test
  @DisplayName("Verify the secure service through administration port")
  void testUpgrade1411to1412ProdOnSecOn() {
    //no changes
    domainNamespace = namespaces.get(4);
    domainUid = "testdomain3";
    adminServerPodName = domainUid + "-" + adminServerName;
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "SSLEnabled=true\n", StandardOpenOption.CREATE);
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "ProductionModeEnabled=true\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "SecureModeEnabled=true\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "AdministrationPortEnabled=true\n", StandardOpenOption.APPEND);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-securemodeon";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "upgrade-model.yaml");

    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = WEBLOGIC_IMAGE_NAME + ":" + "14.1.1.0-11";
    String channelName = "internal-admin";
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, channelName);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    createNginxIngressHostRouting(domainUid, 9002, 7002, 8500, nginxParams.getIngressClassName(), true);

    verifyChannel(domainNamespace, domainUid, List.of(channelName));

    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;

    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", wlsConsoleText, false, ingressIP);    
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminAppIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);

    String image1412 = WEBLOGIC_IMAGE_NAME + ":" + "14.1.2.0";
    image1412 = "wls-docker-dev-local.dockerhub-phx.oci.oraclecorp.com/weblogic:14.1.2.0.0";
    upgradeImage(domainNamespace, domainUid, image1412);
    dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    verifyChannel(domainNamespace, domainUid, List.of(channelName));
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", wlsConsoleText, false, ingressIP);
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminAppIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);    
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);
  }

  /**
   * Test upgrade from 1411 to 1412 with production on and secure mode off.
   */
  @Test
  @DisplayName("Verify the secure service through administration port")
  void testUpgrade1411to1412ProdOnSecNotConfigured() {
    //convert the domain to explicitly disable Secure Mode for the upgrade so that we retain the current 
    //functionality for the user
    domainNamespace = namespaces.get(5);
    domainUid = "testdomain4";
    adminServerPodName = domainUid + "-" + adminServerName;
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "SSLEnabled=false\n", StandardOpenOption.CREATE);
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "ProductionModeEnabled=true\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "AdministrationPortEnabled=true\n", StandardOpenOption.APPEND);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-securemodenotconfigured";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "upgrade-model_1.yaml");

    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = WEBLOGIC_IMAGE_NAME + ":" + "14.1.1.0-11";
    String channelName = "internal-admin";
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, channelName);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    createNginxIngressHostRouting(domainUid, 9002, 7001, 8001, nginxParams.getIngressClassName(), false);

    verifyChannel(domainNamespace, domainUid, List.of(channelName));

    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;

    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminAppIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);    
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", wlsConsoleText, false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);

    String image1412 = "wls-docker-dev-local.dockerhub-phx.oci.oraclecorp.com/weblogic:14.1.2.0.0";
    upgradeImage(domainNamespace, domainUid, image1412);
    dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    verifyChannel(domainNamespace, domainUid, List.of(channelName));
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", wlsConsoleText, false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);
  }

  /**
   * Test upgrade from 1214 to 1412 with production off and secure mode off.
   * 
   * Verify the sample application and console are available in default port 7001 before upgrade.
   * Verify the management REST interface continue to be available in default port 7001 before and after upgrade.
   * Verify the sample application continue to available in default port 7001 after upgrade.
   * Verify the console is moved to a new location in 1412.
   * 
   */
  @Test
  @DisplayName("Test upgrade from 1214 to 1412 with production off and secure mode off")
  void testUpgrade12214to1412ProdOff() {
    domainNamespace = namespaces.get(6);
    domainUid = "testdomain5";
    adminServerPodName = domainUid + "-" + adminServerName;
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "SSLEnabled=false\n", StandardOpenOption.CREATE);
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "ProductionModeEnabled=false\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "AdministrationPortEnabled=false\n", StandardOpenOption.APPEND);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-prod1411off";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "upgrade-model_1.yaml");

    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = WEBLOGIC_IMAGE_NAME + ":" + "14.1.1.0-11";
    String channelName = "default";
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, null);
    createNginxIngressHostRouting(domainUid, 7001, 7002, 8001, nginxParams.getIngressClassName(), false);
    
    verifyChannel(domainNamespace, domainUid, List.of(channelName));
    
    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;

    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);    
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", wlsConsoleText, false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        applicationRuntimes, MII_BASIC_APP_NAME, true, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);

    String image1412 = WEBLOGIC_IMAGE_NAME + ":" + "14.1.2.0";
    image1412 = "wls-docker-dev-local.dockerhub-phx.oci.oraclecorp.com/weblogic:14.1.2.0.0";
    upgradeImage(domainNamespace, domainUid, image1412);
    verifyChannel(domainNamespace, domainUid, List.of(channelName));
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", "This document you requested has moved", false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        applicationRuntimes, MII_BASIC_APP_NAME, true, ingressIP);    
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);
  }

  /**
   * Test upgrade from 1411 to 1412 with production and secure mode off.
   */
  @Test
  @DisplayName("Verify the secure service through administration port")
  void testUpgrade12214to1412ProdOn() {
    domainNamespace = namespaces.get(7);
    domainUid = "testdomain6";
    adminServerPodName = domainUid + "-" + adminServerName;
    Path wdtVariableFile = Paths.get(WORK_DIR, this.getClass().getSimpleName(), "wdtVariable.properties");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(wdtVariableFile);
      Files.createDirectories(wdtVariableFile.getParent());
      Files.writeString(wdtVariableFile, "SSLEnabled=true\n", StandardOpenOption.CREATE);
      Files.writeString(wdtVariableFile, "DomainName=" + domainUid + "\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "ProductionModeEnabled=true\n", StandardOpenOption.APPEND);
      Files.writeString(wdtVariableFile, "AdministrationPortEnabled=true\n", StandardOpenOption.APPEND);
    });

    String auxImageName = DOMAIN_IMAGES_PREFIX + "dci-prod1214on";
    String auxImageTag = getDateAndTimeStamp();
    Path wdtModelFile = Paths.get(RESOURCE_DIR, "securemodeupgrade", "upgrade-model_1.yaml");

    String auxImage = createAuxImage(auxImageName, auxImageTag, wdtModelFile.toString(), wdtVariableFile.toString());
    String baseImage = WEBLOGIC_IMAGE_NAME + ":" + "14.1.1.0-11";
    String channelName = "internal-admin";
    createDomainUsingAuxiliaryImage(domainNamespace, domainUid, baseImage, auxImage, channelName);
    DomainResource dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    createNginxIngressHostRouting(domainUid, 9002, 7002, 8500, nginxParams.getIngressClassName(), true);

    verifyChannel(domainNamespace, domainUid, List.of(channelName));

    String ingressServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    ingressIP = getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) != null
        ? getServiceExtIPAddrtOke(ingressServiceName, ingressNamespace) : K8S_NODEPORT_HOST;

    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminAppIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);
    verifyAppServerAccess(false, getNginxLbNodePort("http"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", wlsConsoleText, false, ingressIP);     
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);

    String image1412 = "wls-docker-dev-local.dockerhub-phx.oci.oraclecorp.com/weblogic:14.1.2.0.0";
    upgradeImage(domainNamespace, domainUid, image1412);
    dcr = assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace));
    logger.info(Yaml.dump(dcr));
    verifyChannel(domainNamespace, domainUid, List.of(channelName));
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminAppIngressHost,
        "/sample-war/index.jsp", adminServerName, false, ingressIP);    
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, adminIngressHost,
        "/console/login/LoginForm.jsp", wlsConsoleText, false, ingressIP);
    verifyAppServerAccess(true, getNginxLbNodePort("https"), true, clusterIngressHost,
        "/sample-war/index.jsp", "ms-1", false, ingressIP);
  }

  private DomainResource createDomainUsingAuxiliaryImage(String domainNamespace, String domainUid,
      String baseImage, String auxImage, String channelName) {
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
    if (channelName != null) {
      Channel channel = domainCR.getSpec().getAdminServer().getAdminService().channels().get(0);
      channel.channelName(channelName);
      domainCR.getSpec().adminServer().adminService().channels(List.of(channel));
    }
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

  private static void installNginx() {
    // install and verify Nginx
    logger.info("Installing Nginx controller using helm");
    nginxParams = installAndVerifyNginx(ingressNamespace, 0, 0);
  }

  private void createNginxIngressHostRouting(String domainUid, int adminPort, int adminSecureAppPort, int msPort,
      String ingressClassName, boolean isTLS) {
    // create an ingress in domain namespace
    String ingressName;

    if (isTLS) {
      ingressName = domainUid + "-nginx-tls";
    } else {
      ingressName = domainUid + "-nginx-nontls";
    }

    // create ingress rules for two domains
    List<V1IngressRule> ingressRules = new ArrayList<>();
    List<V1IngressTLS> tlsList = new ArrayList<>();

    V1HTTPIngressPath clusterIngressPath = new V1HTTPIngressPath()
        .path(null)
        .pathType("ImplementationSpecific")
        .backend(new V1IngressBackend()
            .service(new V1IngressServiceBackend()
                .name(domainUid + "-cluster-mycluster")
                .port(new V1ServiceBackendPort()
                    .number(msPort)))
        );
    V1HTTPIngressPath adminIngressPath = new V1HTTPIngressPath()
        .path(null)
        .pathType("ImplementationSpecific")
        .backend(new V1IngressBackend()
            .service(new V1IngressServiceBackend()
                .name(domainUid + "-adminserver")
                .port(new V1ServiceBackendPort()
                    .number(adminPort)))
        );
    V1HTTPIngressPath adminAppIngressPath = new V1HTTPIngressPath()
        .path(null)
        .pathType("ImplementationSpecific")
        .backend(new V1IngressBackend()
            .service(new V1IngressServiceBackend()
                .name(domainUid + "-adminserver")
                .port(new V1ServiceBackendPort()
                    .number(adminSecureAppPort)))
        );

    // set the ingress rule host
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

    // check the ingress is ready to route the app to the server pod
    int httpNodeport = getNginxLbNodePort("http");
    int httpsNodeport = getNginxLbNodePort("https");
    if (!OKE_CLUSTER) {
      checkIngressReady(true, adminIngressHost, isTLS, httpNodeport, httpsNodeport, "");
      checkIngressReady(true, clusterIngressHost, isTLS, httpNodeport, httpsNodeport, "");
    }

  }

  private static Path tlsCertFile;
  private static Path tlsKeyFile;

  private static void createCertKeyFiles(String cn) {
    assertDoesNotThrow(() -> {
      tlsKeyFile = Files.createTempFile("tls", ".key");
      tlsCertFile = Files.createTempFile("tls", ".crt");
      String command = "openssl req -x509 -nodes -days 365 -newkey rsa:2048 -keyout " + tlsKeyFile
          + " -out " + tlsCertFile + " -subj \"/CN=" + cn + "\"";
      logger.info("Executing command: {0}", command);
      ExecCommand.exec(command, true);
    });
  }

  private static int getNginxLbNodePort(String channelName) {
    String nginxServiceName = nginxParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
    return getServiceNodePort(ingressNamespace, nginxServiceName, channelName);
  }

  private void verifyAppServerAccess(boolean isTLS,
      int lbNodePort,
      boolean isHostRouting,
      String ingressHostName,
      String pathLocation,
      String content,
      boolean useCredentials,
      String... hostName) {

    StringBuffer url = new StringBuffer();
    String hostAndPort;
    if (hostName != null && hostName.length > 0) {
      hostAndPort = OKE_CLUSTER_PRIVATEIP ? hostName[0] : hostName[0] + ":" + lbNodePort;
    } else {
      String host = K8S_NODEPORT_HOST;
      if (host.contains(":")) {
        host = "[" + host + "]";
      }
      hostAndPort = host + ":" + lbNodePort;
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
    assertThat(assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace)
        .getSpec().getAdminServer().getAdminService().getChannels().stream().count())
        .equals(Long.valueOf(channelNames.size())))
        .withFailMessage("Number of channels are not equal to expected length");

    for (String channelName : channelNames) {
      assertThat(assertDoesNotThrow(() -> getDomainCustomResource(domainUid, domainNamespace)
          .getSpec().getAdminServer().getAdminService().getChannels().stream()
          .anyMatch(ch -> ch.channelName().equals(channelName))))
          .as(String.format("Channel %s was found in domain resource %s", channelName, domainUid))
          .withFailMessage(String.format("Channel %s was found not in domain resource %s", channelName, domainUid))
          .isEqualTo(true);
    }
  }

}
