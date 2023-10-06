// Copyright (c) 2020, 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.verrazzano.weblogic.kubernetes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.Yaml;
import oracle.verrazzano.weblogic.ApplicationConfiguration;
import oracle.verrazzano.weblogic.ApplicationConfigurationSpec;
import oracle.verrazzano.weblogic.Component;
import oracle.verrazzano.weblogic.ComponentSpec;
import oracle.verrazzano.weblogic.Components;
import oracle.verrazzano.weblogic.Destination;
import oracle.verrazzano.weblogic.IngressRule;
import oracle.verrazzano.weblogic.IngressTrait;
import oracle.verrazzano.weblogic.IngressTraitSpec;
import oracle.verrazzano.weblogic.IngressTraits;
import oracle.verrazzano.weblogic.Path;
import oracle.verrazzano.weblogic.Workload;
import oracle.verrazzano.weblogic.WorkloadSpec;
import oracle.verrazzano.weblogic.kubernetes.annotations.VzIntegrationTest;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.ClusterService;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.DomainResource;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.primitive.HelmParams;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ExecCommand;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.IMAGE_PULL_POLICY;
import static oracle.weblogic.kubernetes.TestConstants.LOGS_DIR;
import static oracle.weblogic.kubernetes.TestConstants.TEST_IMAGES_REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.actions.TestActions.execCommand;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.getServicePort;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.createApplication;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.createComponent;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createDomainResource;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNextFreePort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.isServiceExtIPAddrtOkeReady;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.withLongRetryPolicy;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createMiiImageAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createTestRepoSecret;
import static oracle.weblogic.kubernetes.utils.ImageUtils.imageRepoLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.LoadBalancerUtils.createTraefikIngressRoutingRules;
import static oracle.weblogic.kubernetes.utils.LoadBalancerUtils.getLbExternalIp;
import static oracle.weblogic.kubernetes.utils.LoadBalancerUtils.installAndVerifyTraefik;
import static oracle.weblogic.kubernetes.utils.PodUtils.setPodAntiAffinity;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.getIstioHost;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.getLoadbalancerAddress;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.setLabelToNamespace;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.verifyVzApplicationAccess;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test is used for testing the affinity between a web client and a WebLogic server
 * for the duration of a HTTP session using Traefik ingress controllers
 * as well as cluster service.
 */
@DisplayName("Test sticky sessions management with Traefik and ClusterService")
@VzIntegrationTest
@Tag("v8o")    
class ItVzStickySession {

  // constants for creating domain image using model in image
  private static final String SESSMIGR_MODEL_FILE = "model.stickysess.yaml";
  private static final String SESSMIGR_IMAGE_NAME = "mii-image";

  // constants for web service
  private static final String SESSMIGR_APP_NAME = "stickysess-app";
  private static final String SESSMIGR_APP_WAR_NAME = "stickysess-war";
  private static final int SESSION_STATE = 4;
  private static Map<String, String> httpAttrMap;

  // constants for operator and WebLogic domain
  private static String domainUid = "stickysess-domain-1";
  private static String clusterName = "cluster-1";
  private static String adminServerPodName = domainUid + "-admin-server";
  private static String managedServerPrefix = domainUid + "-managed-server";
  private static int managedServerPort = 8001;
  private static int replicaCount = 1;
  private static String domainNamespace = null;
  private static String traefikNamespace = null;

  // constants for Traefik
  private static HelmParams traefikHelmParams = null;
  private static LoggingFacade logger = null;

  /**
   * Install Traefik and operator, create a custom image using model in image
   * with model files and create a one cluster domain.
   *
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   *                   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void init(@Namespaces(2) List<String> namespaces) {
    logger = getLogger();

    // get a unique Traefik namespace
    logger.info("Get a unique namespace for Traefik");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    traefikNamespace = namespaces.get(0);

    // get a unique domain namespace
    logger.info("Get a unique namespace for WebLogic domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domainNamespace = namespaces.get(1);
    
    assertDoesNotThrow(() -> setLabelToNamespace(Arrays.asList(domainNamespace)));
    
    traefikHelmParams =
          installAndVerifyTraefik(traefikNamespace, 0, 0);
    
    // create and verify WebLogic domain image using model in image with model files
    String imageName = createAndVerifyDomainImage();

    // create and verify one cluster domain
    logger.info("Create domain and verify that it's running");
    createAndVerifyDomain(imageName);

    // map to save HTTP response data
    httpAttrMap = new HashMap<String, String>();
    httpAttrMap.put("sessioncreatetime", "(.*)sessioncreatetime>(.*)</sessioncreatetime(.*)");
    httpAttrMap.put("sessionid", "(.*)sessionid>(.*)</sessionid(.*)");
    httpAttrMap.put("servername", "(.*)connectedservername>(.*)</connectedservername(.*)");
    httpAttrMap.put("count", "(.*)countattribute>(.*)</countattribute(.*)");
  }

  

  /**
   * Verify that using Traefik ingress controller, two HTTP requests sent to WebLogic
   * are directed to same WebLogic server.
   * The test uses a web application deployed on WebLogic cluster to track HTTP session.
   * server-affinity is achieved by Traefik ingress controller based on HTTP session information.
   */
  @Test
  @DisplayName("Create a Traefik ingress resource and verify that two HTTP connections are sticky to the same server")
  void testSameSessionStickinessUsingTraefik() {    
    final String ingressServiceName = traefikHelmParams.getReleaseName();
    final String channelName = "web";

    // create Traefik ingress resource
    final String ingressResourceFileName = "traefik/traefik-ingress-rules-stickysession.yaml";
    createTraefikIngressRoutingRules(domainNamespace, traefikNamespace, ingressResourceFileName, domainUid);

    String hostName = new StringBuffer()
        .append(domainUid)
        .append(".")
        .append(domainNamespace)
        .append(".")
        .append(clusterName)
        .append(".test").toString();

    // get Traefik ingress service Nodeport
    int ingressServiceNodePort =
        getIngressServiceNodePort(traefikNamespace, ingressServiceName, channelName);

    // verify that two HTTP connections are sticky to the same server
    sendHttpRequestsToTestSessionStickinessAndVerify(hostName, ingressServiceNodePort);
  }

  /**
   * Verify that using cluster service, two HTTP requests sent to WebLogic
   * are directed to same WebLogic server.
   * The test uses a web application deployed on WebLogic cluster to track HTTP session.
   * server-affinity is achieved by cluster service based on HTTP session information.
   */
  @Test
  @DisplayName("Verify that two HTTP connections are sticky to the same server using cluster service")
  void testSameSessionStickinessUsingClusterService() {
    //build cluster hostname
    String hostName = new StringBuffer()
        .append(domainUid)
        .append(".")
        .append(domainNamespace)
        .append(".")
        .append(clusterName)
        .append(".test").toString();

    //build cluster address
    final String clusterAddress = domainUid + "-cluster-" + clusterName;
    //get cluster port
    int clusterPort = assertDoesNotThrow(()
        -> getServicePort(domainNamespace, clusterAddress, "default"),
        "Getting admin server default port failed");
    assertFalse(clusterPort == 0 || clusterPort < 0, "cluster Port is an invalid number");
    logger.info("cluster port for cluster server {0} is: {1}", clusterAddress, clusterPort);

    // verify that two HTTP connections are sticky to the same server
    sendHttpRequestsToTestSessionStickinessAndVerify(hostName, clusterPort, clusterAddress);
  }

  private static String createAndVerifyDomainImage() {
    // create image with model files
    logger.info("Create image with model file and verify");
    String miiImage =
        createMiiImageAndVerify(SESSMIGR_IMAGE_NAME, SESSMIGR_MODEL_FILE, SESSMIGR_APP_NAME);

    // repo login and push image to registry if necessary
    imageRepoLoginAndPushImageToRegistry(miiImage);

    // create registry secret to pull the image from registry
    // this secret is used only for non-kind cluster
    logger.info("Create registry secret in namespace {0}", domainNamespace);
    createTestRepoSecret(domainNamespace);

    return miiImage;
  }

  private static void createAndVerifyDomain(String miiImage) {
    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(adminSecretName, domainNamespace,
        ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT),
        String.format("create secret for admin credentials failed for %s", adminSecretName));

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(encryptionSecretName, domainNamespace,
        "weblogicenc", "weblogicenc"),
        String.format("create encryption secret failed for %s", encryptionSecretName));

    // create cluster object
    String clusterName = "cluster-1";
    ClusterService myClusterService = new ClusterService();
    myClusterService.setSessionAffinity("ClientIP");
    DomainResource domain = createDomainResource(domainUid, domainNamespace, miiImage,
        adminSecretName, new String[]{TEST_IMAGES_REPO_SECRET_NAME},
        encryptionSecretName, replicaCount, Arrays.asList(clusterName));

    Component component = new Component()
        .apiVersion("core.oam.dev/v1alpha2")
        .kind("Component")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domainNamespace))
        .spec(new ComponentSpec()
            .workLoad(new Workload()
                .apiVersion("oam.verrazzano.io/v1alpha1")
                .kind("VerrazzanoWebLogicWorkload")
                .spec(new WorkloadSpec()
                    .template(domain))));

    Map<String, String> keyValueMap = new HashMap<>();
    keyValueMap.put("version", "v1.0.0");
    keyValueMap.put("description", "My vz wls application");

    ApplicationConfiguration application = new ApplicationConfiguration()
        .apiVersion("core.oam.dev/v1alpha2")
        .kind("ApplicationConfiguration")
        .metadata(new V1ObjectMeta()
            .name("myvzdomain")
            .namespace(domainNamespace)
            .annotations(keyValueMap))
        .spec(new ApplicationConfigurationSpec()
            .components(Arrays.asList(new Components()
                .componentName(domainUid)
                .traits(Arrays.asList(new IngressTraits()
                    .trait(new IngressTrait()
                        .apiVersion("oam.verrazzano.io/v1alpha1")
                        .kind("IngressTrait")
                        .metadata(new V1ObjectMeta()
                            .name("mysessiondomain-ingress")
                            .namespace(domainNamespace))
                        .spec(new IngressTraitSpec()
                            .ingressRules(Arrays.asList(
                                new IngressRule()
                                    .paths(Arrays.asList(new Path()
                                        .path("/console")
                                        .pathType("Prefix")))
                                    .destination(new Destination()
                                        .host(adminServerPodName)
                                        .port(7001)),
                                new IngressRule()
                                    .paths(Arrays.asList(new Path()
                                        .path("/" + SESSMIGR_APP_WAR_NAME)
                                        .pathType("Prefix")))
                                    .destination(new Destination()
                                        .host(domainUid + "-cluster-" + clusterName)
                                        .port(managedServerPort)))))))))));

    logger.info(Yaml.dump(component));
    logger.info(Yaml.dump(application));

    logger.info("Deploying components");
    assertDoesNotThrow(() -> createComponent(component));
    logger.info("Deploying application");
    assertDoesNotThrow(() -> createApplication(application));

    // check admin server pod is ready
    logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReadyAndServiceExists(adminServerPodName, domainUid, domainNamespace);
    // check managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed server pod {0} to be ready in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkPodReadyAndServiceExists(managedServerPrefix + i, domainUid, domainNamespace);
    }

    // get istio gateway host and loadbalancer address
    String host = getIstioHost(domainNamespace);
    String address = getLoadbalancerAddress();

    // verify WebLogic console page is accessible through istio/loadbalancer
    String message = "Oracle WebLogic Server Administration Console";
    String consoleUrl = "https://" + host + "/console/login/LoginForm.jsp --resolve " + host + ":443:" + address;
    assertTrue(verifyVzApplicationAccess(consoleUrl, message), "Failed to get WebLogic administration console");

  }

  private static void createDomainCrAndVerify(String adminSecretName,
                                              String repoSecretName,
                                              String encryptionSecretName,
                                              String miiImage) {

    ClusterService myClusterService = new ClusterService();
    myClusterService.setSessionAffinity("ClientIP");

    // create the domain CR
    DomainResource domain = new DomainResource()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domainNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
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
                    .value("-Dweblogic.StdoutDebugEnabled=false"))
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
                    .domainType("WLS")
                    .runtimeEncryptionSecret(encryptionSecretName))
                .introspectorJobActiveDeadlineSeconds(300L)));

    setPodAntiAffinity(domain);

    // create domain using model in image
    logger.info("Create model in image domain {0} in namespace {1} using image {2}",
        domainUid, domainNamespace, miiImage);
    createDomainAndVerify(domain, domainNamespace);
  }

  private Map<String, String> getServerAndSessionInfoAndVerify(String hostName,
                                                               int servicePort,
                                                               String curlUrlPath,
                                                               String headerOption,
                                                               String... clusterAddress) {
    final String serverNameAttr = "servername";
    final String sessionIdAttr = "sessionid";
    final String countAttr = "count";

    // send a HTTP request
    logger.info("Process HTTP request in host {0} and servicePort {1} ",
        hostName, servicePort);
    Map<String, String> httpAttrInfo =
        processHttpRequest(hostName, servicePort, curlUrlPath, headerOption, clusterAddress);

    // get HTTP response data
    String serverName = httpAttrInfo.get(serverNameAttr);
    String sessionId = httpAttrInfo.get(sessionIdAttr);
    String countStr = httpAttrInfo.get(countAttr);

    // verify that the HTTP response data are not null
    assertAll("Check that WebLogic server and session vars is not null or empty",
        () -> assertNotNull(serverName,"Server name shouldn’t be null"),
        () -> assertNotNull(sessionId,"Session ID shouldn’t be null"),
        () -> assertNotNull(countStr,"Session state shouldn’t be null")
    );

    // map to save server and session info
    Map<String, String> httpDataInfo = new HashMap<String, String>();
    httpDataInfo.put(serverNameAttr, serverName);
    httpDataInfo.put(sessionIdAttr, sessionId);
    httpDataInfo.put(countAttr, countStr);

    return httpDataInfo;
  }

  private static Map<String, String> processHttpRequest(String hostName,
                                                        int servicePort,
                                                        String curlUrlPath,
                                                        String headerOption,
                                                        String... clusterAddress) {
    String[] httpAttrArray =
        {"sessioncreatetime", "sessionid", "servername", "count"};
    Map<String, String> httpAttrInfo = new HashMap<String, String>();

    // build curl command
    String curlCmd =
        buildCurlCommand(hostName, servicePort, curlUrlPath, headerOption, clusterAddress);
    logger.info("Command to set HTTP request or get HTTP response {0} ", curlCmd);
    ExecResult execResult = null;

    if (clusterAddress.length == 0) {
      // set HTTP request and get HTTP response in a local machine
      execResult = assertDoesNotThrow(() -> ExecCommand.exec(curlCmd, true));
    } else {
      // set HTTP request and get HTTP response in admin pod
      execResult = assertDoesNotThrow(() -> execCommand(domainNamespace, adminServerPodName,
          null, true, "/bin/sh", "-c", curlCmd));
    }

    if (execResult.exitValue() == 0) {
      assertNotNull(execResult.stdout(), "Primary server name should not be null");
      assertFalse(execResult.stdout().isEmpty(), "Primary server name should not be empty");
      logger.info("\n HTTP response is \n " + execResult.stdout());

      for (String httpAttrKey : httpAttrArray) {
        String httpAttrValue = getHttpResponseAttribute(execResult.stdout(), httpAttrKey);
        httpAttrInfo.put(httpAttrKey, httpAttrValue);
      }
    } else {
      fail("Failed to process HTTP request " + execResult.stderr());
    }

    return httpAttrInfo;
  }

  private static String buildCurlCommand(String hostName,
      int servicePort,
      String curlUrlPath,
      String headerOption,
      String... clusterAddress) {

    StringBuffer curlCmd = new StringBuffer("curl --show-error");
    logger.info("Build a curl command with hostname {0} and port {1}", hostName, servicePort);

    if (clusterAddress.length == 0) {
      //use a LBer ingress controller to build the curl command to run on local
      final String ingressServiceName = traefikHelmParams.getReleaseName();
      final String httpHeaderFile = LOGS_DIR + "/headers";
      logger.info("Build a curl command with hostname {0} and port {1}", hostName, servicePort);

      String serviceExtIPAddr = null;
      testUntil(withLongRetryPolicy, isServiceExtIPAddrtOkeReady(ingressServiceName, traefikNamespace),
          logger, "Waiting until external IP address of the service available");
      serviceExtIPAddr = assertDoesNotThrow(() -> getLbExternalIp(ingressServiceName, traefikNamespace),
              "Can't find external IP address of the service " + ingressServiceName);
      logger.info("External IP address of the service is {0} ", serviceExtIPAddr);

      curlCmd.append(" --noproxy '*' -H 'host: ")
          .append(hostName)
          .append("' http://")
          .append(serviceExtIPAddr + ":" + servicePort)
          .append("/")
          .append(curlUrlPath)
          .append(headerOption)
          .append(httpHeaderFile);
    } else {
      //use cluster service to build the curl command to run in admin pod
      // save the cookie file to /u01 in order to run the test on openshift env
      final String httpHeaderFile = "/u01/header";
      logger.info("Build a curl command with pod name {0}, curl URL path {1} and HTTP header option {2}",
          clusterAddress[0], curlUrlPath, headerOption);

      int waittime = 5;
      curlCmd.append(" --silent --connect-timeout ")
          .append(waittime)
          .append(" --max-time ").append(waittime)
          .append(" http://")
          .append(clusterAddress[0])
          .append(":")
          .append(servicePort)
          .append("/")
          .append(curlUrlPath)
          .append(headerOption)
          .append(httpHeaderFile);
    }

    return curlCmd.toString();
  }

  private static String getHttpResponseAttribute(String httpResponseString, String attribute) {
    // retrieve the search pattern that matches the given HTTP data attribute
    String attrPatn = httpAttrMap.get(attribute);
    assertNotNull(attrPatn,"HTTP Attribute key shouldn’t be null");

    // search the value of given HTTP data attribute
    Pattern pattern = Pattern.compile(attrPatn);
    Matcher matcher = pattern.matcher(httpResponseString);
    String httpAttribute = null;

    if (matcher.find()) {
      httpAttribute = matcher.group(2);
    }

    return httpAttribute;
  }

  private int getIngressServiceNodePort(String nameSpace, String ingressServiceName, String channelName) {
    // get ingress service Nodeport
    int ingressServiceNodePort = assertDoesNotThrow(() ->
            getServiceNodePort(nameSpace, ingressServiceName, channelName),
        "Getting web node port for Traefik loadbalancer failed");
    logger.info("Node port for {0} is: {1} :", ingressServiceName, ingressServiceNodePort);

    return ingressServiceNodePort;
  }

  private void sendHttpRequestsToTestSessionStickinessAndVerify(String hostname,
                                                                int servicePort,
                                                                String... clusterAddress) {
    final int counterNum = 4;
    final String webServiceSetUrl = SESSMIGR_APP_WAR_NAME + "/?setCounter=" + counterNum;
    final String webServiceGetUrl = SESSMIGR_APP_WAR_NAME + "/?getCounter";
    final String serverNameAttr = "servername";
    final String sessionIdAttr = "sessionid";
    final String countAttr = "count";

    // send a HTTP request to set http session state(count number) and save HTTP session info
    Map<String, String> httpDataInfo = getServerAndSessionInfoAndVerify(hostname,
            servicePort, webServiceSetUrl, " -c ", clusterAddress);
    // get server and session info from web service deployed on the cluster
    String serverName1 = httpDataInfo.get(serverNameAttr);
    String sessionId1 = httpDataInfo.get(sessionIdAttr);
    logger.info("Got the server {0} and session ID {1} from the first HTTP connection",
        serverName1, sessionId1);

    // send a HTTP request again to get server and session info
    httpDataInfo = getServerAndSessionInfoAndVerify(hostname,
        servicePort, webServiceGetUrl, " -b ", clusterAddress);
    // get server and session info from web service deployed on the cluster
    String serverName2 = httpDataInfo.get(serverNameAttr);
    String sessionId2 = httpDataInfo.get(sessionIdAttr);
    String countStr = httpDataInfo.get(countAttr);
    int count = Optional.ofNullable(countStr).map(Integer::valueOf).orElse(0);
    logger.info("Got the server {0}, session ID {1} and session state {2} "
        + "from the second HTTP connection", serverName2, sessionId2, count);

    // verify that two HTTP connections are sticky to the same server
    assertAll("Check that the sticky session is supported",
        () -> assertEquals(serverName1, serverName2,
            "HTTP connections should be sticky to the server " + serverName1),
        () -> assertEquals(sessionId1, sessionId2,
            "HTTP session ID should be same for all HTTP connections " + sessionId1),
        () -> assertEquals(SESSION_STATE, count,
            "HTTP session state should equels " + SESSION_STATE)
    );

    logger.info("SUCCESS --- test same session stickiness \n"
        + "Two HTTP connections are sticky to server {0} The session state "
        + "from the second HTTP connections is {2}", serverName2, SESSION_STATE);
  }
}
