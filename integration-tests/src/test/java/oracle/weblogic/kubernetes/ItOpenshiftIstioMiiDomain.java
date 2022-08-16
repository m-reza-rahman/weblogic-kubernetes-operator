// Copyright (c) 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1SecretReference;
import io.kubernetes.client.util.Yaml;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.OnlineUpdate;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.primitive.Command;
import oracle.weblogic.kubernetes.actions.impl.primitive.CommandParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.HelmParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ExecResult;
import oracle.weblogic.kubernetes.utils.FileUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_CHART_DIR;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_RELEASE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.TEST_IMAGES_REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.patchDomainResourceWithNewIntrospectVersion;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.checkAppUsingHostHeader;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.replaceConfigMapWithModelFiles;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyIntrospectorRuns;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyPodIntrospectVersionUpdated;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyPodsNotRolled;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.withStandardRetryPolicy;
import static oracle.weblogic.kubernetes.utils.ConfigMapUtils.createConfigMapAndVerify;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.ExecCommand.exec;
import static oracle.weblogic.kubernetes.utils.FileUtils.generateFileFromTemplate;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createTestRepoSecret;
import static oracle.weblogic.kubernetes.utils.IstioUtils.createAdminServer;
import static oracle.weblogic.kubernetes.utils.IstioUtils.deployHttpIstioGatewayAndVirtualservice;
import static oracle.weblogic.kubernetes.utils.IstioUtils.deployIstioDestinationRule;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.PodUtils.getPodCreationTime;
import static oracle.weblogic.kubernetes.utils.PodUtils.setPodAntiAffinity;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 * A sample mii domain tests running in Openshift service mesh. The test assumes that the service mesh istio operator is
 * running in istio-system namespace. All the service mesh related operators are installed and running as per this
 * documentation. https://docs.openshift.com/container-platform/4.10/service_mesh/v2x/installing-ossm.html
 */
@DisplayName("Test Openshift servce mesh istio enabled WebLogic Domain in mii model")
@Tag("openshift")
@IntegrationTest
class ItOpenshiftIstioMiiDomain {

  private static String opNamespace = null;
  private static String domainNamespace = null;

  private String domainUid = "openshift-istio-mii";
  private String configMapName = "dynamicupdate-istio-configmap";
  private final String clusterName = "cluster-1"; // do not modify
  private final String adminServerPodName = domainUid + "-admin-server";
  private final String managedServerPrefix = domainUid + "-managed-server";
  private final String workManagerName = "newWM";
  private final int replicaCount = 2;

  private static String testWebAppWarLoc = null;

  private static LoggingFacade logger = null;

  /**
   * Install Operator.
   * @param namespaces list of namespaces created by the IntegrationTestWatcher
  */
  @BeforeAll
  public static void initAll(@Namespaces(2) List<String> namespaces) {
    logger = getLogger();

    // get a new unique opNamespace
    logger.info("Assign unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    logger.info("Assign unique namespace for Domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domainNamespace = namespaces.get(1);
    
    // edit service member roll to include operator and domain namespace so that 
    // Openshift service mesh can add istio side cars to the operator and WebLogic pods.
    Path smrYaml = Paths.get(WORK_DIR, "openshift", "servicememberroll.yaml");
    assertDoesNotThrow(() -> {
      FileUtils.copy(Paths.get(RESOURCE_DIR, "openshift", "servicememberroll.yaml"),
          smrYaml);
      FileUtils.replaceStringInFile(smrYaml.toString(), "OPERATOR_NAMESPACE", opNamespace);
      FileUtils.replaceStringInFile(smrYaml.toString(), "DOMAIN_NAMESPACE", domainNamespace);
    });
    logger.info("Run kubectl to create the service member roll");
    CommandParams params = new CommandParams().defaults();
    params.command("kubectl apply -f " + smrYaml.toString());
    boolean result = Command.withParams(params).execute();
    assertTrue(result, "Failed to create service member roll");

    // install and verify operator with istio side car injection set to true
    String opSa = opNamespace + "-sa";
    HelmParams opHelmParams
        = new HelmParams().releaseName(OPERATOR_RELEASE_NAME)
            .namespace(opNamespace)
            .chartDir(OPERATOR_CHART_DIR);
    
    installAndVerifyOperator(opNamespace,
        opSa, // operator service account
        false, // with REST api enabled
        0, // externalRestHttpPort
        opHelmParams, // operator helm parameters
        false, // ElkintegrationEnabled
        null, // domainspaceSelectionStrategy
        null, // domainspaceSelector
        false, // enableClusterRolebinding
        "INFO", // operator pod log level
        -1, // domainPresenceFailureRetryMaxCount
        -1, // domainPresenceFailureRetrySeconds
        true, // openshift istio injection
        domainNamespace // domainNamespace
    );
  }

  /**
   * Create a domain using model-in-image model.
   * Inject sidecar.istio.io in domain resource under global serverPod object.
   * Set localhostBindingsEnabled to true in istio configuration. 
   * Add istio configuration with default readinessPort.
   * Do not add any AdminService under AdminServer configuration.
   * Deploy istio gateways and virtual service.
   * Verify server pods are in ready state and services are created.
   * Verify login to WebLogic console is successful thru istio ingress port.
   * Deploy a web application thru istio http ingress port using REST api.  
   * Access web application thru istio http ingress port using curl.
   */
  @Test
  @DisplayName("Create WebLogic Domain with mii model with openshift service mesh")  
  void testIstioModelInImageDomain() {

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    createTestRepoSecret(domainNamespace);

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
                                    adminSecretName,
                                    domainNamespace,
                                    ADMIN_USERNAME_DEFAULT,
                                    ADMIN_PASSWORD_DEFAULT),
        String.format("createSecret failed for %s", adminSecretName));

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
                                      encryptionSecretName,
                                      domainNamespace,
                            "weblogicenc",
                            "weblogicenc"),
                    String.format("createSecret failed for %s", encryptionSecretName));

    // create WDT config map without any files
    createConfigMapAndVerify(configMapName, domainUid, domainNamespace, Collections.emptyList());
    // create the domain object
    Domain domain = createDomainResource(domainUid,
                                      domainNamespace,
                                      adminSecretName,
                                      TEST_IMAGES_REPO_SECRET_NAME,
                                      encryptionSecretName,
                                      replicaCount,
                              MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG,
                              configMapName);
    domain.spec().serverPod()
        .annotations((Map<String, String>) new HashMap().put("sidecar.istio.io/inject", "true"));
    domain.spec().configuration().istio().localhostBindingsEnabled(true);
    logger.info(Yaml.dump(domain));

    // create model in image domain
    createDomainAndVerify(domain, domainNamespace);

    logger.info("Check admin service {0} is created in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReadyAndServiceExists(adminServerPodName, domainUid, domainNamespace);
    // check managed server services created
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check managed service {0} is created in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkPodReadyAndServiceExists(managedServerPrefix + i, domainUid, domainNamespace);
    }

    String clusterService = domainUid + "-cluster-" + clusterName + "." + domainNamespace + ".svc.cluster.local";

    Map<String, String> templateMap  = new HashMap<>();
    templateMap.put("NAMESPACE", domainNamespace);
    templateMap.put("DUID", domainUid);
    templateMap.put("ADMIN_SERVICE",adminServerPodName);
    templateMap.put("CLUSTER_SERVICE", clusterService);

    Path srcHttpFile = Paths.get(RESOURCE_DIR, "istio", "istio-http-template.yaml");
    Path targetHttpFile = assertDoesNotThrow(
        () -> generateFileFromTemplate(srcHttpFile.toString(), "openshift-istio-http.yaml", templateMap));
    logger.info("Generated Http VS/Gateway file path is {0}", targetHttpFile);

    boolean deployRes = assertDoesNotThrow(
        () -> deployHttpIstioGatewayAndVirtualservice(targetHttpFile));
    assertTrue(deployRes, "Failed to deploy Http Istio Gateway/VirtualService");

    Path srcDrFile = Paths.get(RESOURCE_DIR, "istio", "istio-dr-template.yaml");
    Path targetDrFile = assertDoesNotThrow(
        () -> generateFileFromTemplate(srcDrFile.toString(), "openshift-istio-dr.yaml", templateMap));
    logger.info("Generated DestinationRule file path is {0}", targetDrFile);

    deployRes = assertDoesNotThrow(
        () -> deployIstioDestinationRule(targetDrFile));
    assertTrue(deployRes, "Failed to deploy Istio DestinationRule");
    
    // get gateway url
    // oc -n istio-system get route istio-ingressgateway -o jsonpath='{.spec.host}'
    logger.info("Run kubectl to get ingress gateway route");
    CommandParams params = new CommandParams().defaults();
    params.command("kubectl get route -n istio-system istio-ingressgateway -o jsonpath='{.spec.host}'");
    ExecResult result = Command.withParams(params).executeAndReturnResult();
    assertEquals(0, result.exitValue());
    assertNotNull(result.stdout());
    String gatewayUrl = result.stdout();
    
    String consoleUrl = gatewayUrl + "/console/login/LoginForm.jsp";
    boolean checkConsole = checkAppUsingHostHeader(consoleUrl, domainNamespace + ".org");
    assertTrue(checkConsole, "Failed to access WebLogic console");
    logger.info("WebLogic console is accessible");

    Path archivePath = Paths.get(testWebAppWarLoc);
    
    StringBuffer headerString = null;
    headerString = new StringBuffer("-H 'host: ");
    headerString.append(domainNamespace + ".org")
        .append(" ' ");
    StringBuffer curlString = new StringBuffer("status=$(curl --noproxy '*' ");
    curlString.append(" --user " + ADMIN_USERNAME_DEFAULT + ":" + ADMIN_PASSWORD_DEFAULT);
    curlString.append(" -w %{http_code} --show-error -o /dev/null ")
        .append(headerString.toString())
        .append("-H X-Requested-By:MyClient ")
        .append("-H Accept:application/json  ")
        .append("-H Content-Type:multipart/form-data ")
        .append("-H Prefer:respond-async ")
        .append("-F \"model={ name: '")
        .append("testwebapp")
        .append("', targets: [ ")
        .append(clusterName)
        .append(" ] }\" ")
        .append(" -F \"sourcePath=@")
        .append(archivePath.toString() + "\" ")
        .append("-X POST http://" + gatewayUrl)
        .append("/management/weblogic/latest/edit/appDeployments); ")
        .append("echo ${status}");

    logger.info("deployUsingRest: curl command {0}", new String(curlString));
    try {
      result = exec(new String(curlString), true);
    } catch (Exception ex) {
      logger.info("deployUsingRest: caught unexpected exception {0}", ex);
    }

    assertNotNull(result, "Application deployment failed");
    logger.info("Application deployment returned {0}", result.toString());
    assertEquals("202", result.stdout(), "Deployment didn't return HTTP status code 202");

    String url = "http://" + gatewayUrl + "/testwebapp/index.jsp";
    logger.info("Application Access URL {0}", url);
    boolean checkApp = checkAppUsingHostHeader(url, domainNamespace + ".org");
    assertTrue(checkApp, "Failed to access WebLogic application");

    //Verify the dynamic configuration update
    LinkedHashMap<String, OffsetDateTime> pods = new LinkedHashMap<>();
    // get the creation time of the admin server pod before patching
    OffsetDateTime adminPodCreationTime = getPodCreationTime(domainNamespace, adminServerPodName);
    pods.put(adminServerPodName, getPodCreationTime(domainNamespace, adminServerPodName));
    // get the creation time of the managed server pods before patching
    for (int i = 1; i <= replicaCount; i++) {
      pods.put(managedServerPrefix + i, getPodCreationTime(domainNamespace, managedServerPrefix + i));
    }
    for (int i = 1; i <= replicaCount; i++) {
      pods.put(managedServerPrefix + i, getPodCreationTime(domainNamespace, managedServerPrefix + i));
    }

    replaceConfigMapWithModelFiles(configMapName, domainUid, domainNamespace,
        Arrays.asList(MODEL_DIR + "/model.config.wm.yaml"), withStandardRetryPolicy);

    String introspectVersion = patchDomainResourceWithNewIntrospectVersion(domainUid, domainNamespace);

    verifyIntrospectorRuns(domainUid, domainNamespace);

    String wmRuntimeUrl  = "http://" + gatewayUrl + "/management/weblogic/latest/domainRuntime"
           + "/serverRuntimes/managed-server1/applicationRuntimes"
           + "/testwebapp/workManagerRuntimes/newWM/"
           + "maxThreadsConstraintRuntime ";

    boolean checkWm =
          checkAppUsingHostHeader(wmRuntimeUrl, domainNamespace + ".org");
    assertTrue(checkWm, "Failed to access WorkManagerRuntime");
    logger.info("Found new work manager runtime");

    verifyPodsNotRolled(domainNamespace, pods);
    verifyPodIntrospectVersionUpdated(pods.keySet(), introspectVersion, domainNamespace);
  }

  private Domain createDomainResource(String domainUid, String domNamespace,
           String adminSecretName, String repoSecretName,
           String encryptionSecretName, int replicaCount,
           String miiImage, String configmapName) {

    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .imagePullPolicy("IfNotPresent")
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(repoSecretName))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IfNeeded")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.StdoutDebugEnabled=false -Dweblogic.rjvm.enableprotocolswitch=true"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom ")))
            .adminServer(createAdminServer())
            .addClustersItem(new Cluster()
                .clusterName(clusterName)
                .replicas(replicaCount))
            .configuration(new Configuration()
                     .model(new Model()
                         .domainType("WLS")
                         .configMap(configmapName)
                         .onlineUpdate(new OnlineUpdate().enabled(true))
                         .runtimeEncryptionSecret(encryptionSecretName))
            .introspectorJobActiveDeadlineSeconds(300L)));
    setPodAntiAffinity(domain);
    return domain;
  }
}
