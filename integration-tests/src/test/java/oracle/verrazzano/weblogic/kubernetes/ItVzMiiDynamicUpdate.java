// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.verrazzano.weblogic.kubernetes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import io.kubernetes.client.openapi.ApiException;
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
import oracle.verrazzano.weblogic.Workload;
import oracle.verrazzano.weblogic.WorkloadSpec;
import oracle.verrazzano.weblogic.kubernetes.annotations.VzIntegrationTest;
import oracle.weblogic.domain.DomainResource;
import oracle.weblogic.kubernetes.actions.impl.primitive.Command;
import oracle.weblogic.kubernetes.actions.impl.primitive.CommandParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ConfigMapUtils;
import oracle.weblogic.kubernetes.utils.ExecResult;
import oracle.weblogic.kubernetes.utils.MiiDynamicUpdateHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.KUBERNETES_CLI;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_DEPLOYMENT_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.TEST_IMAGES_REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.patchDomainResourceWithNewIntrospectVersion;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.createApplication;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.createComponent;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.deleteComponent;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createDomainResource;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.replaceConfigMapWithModelFiles;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyIntrospectorRuns;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyPodIntrospectVersionUpdated;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyPodsNotRolled;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.withStandardRetryPolicy;
import static oracle.weblogic.kubernetes.utils.ExecCommand.exec;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createTestRepoSecret;
import static oracle.weblogic.kubernetes.utils.PatchDomainUtils.patchDomainResourceWithNewReplicaCountAtSpecLevel;
import static oracle.weblogic.kubernetes.utils.PodUtils.getPodCreationTime;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.getIstioHost;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.getLoadbalancerAddress;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.setLabelToNamespace;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.verifyVzApplicationAccess;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test class verifies adding work manager, adding cluster, in a running WebLogic domain through dynamic update.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Test dynamic updates to a model in image domain")
@VzIntegrationTest
@Tag("v8o")
class ItVzMiiDynamicUpdate {

  private static String domainNamespace = null;
  private static final String domainUid = "vz-mii-dynamic-update";
  // admin/managed server name here should match with model yaml in MII_BASIC_WDT_MODEL_FILE
  static final String adminServerPodName = domainUid + "-admin-server";
  static final String managedServerPrefix = domainUid + "-managed-server";
  static final int replicaCount = 2;
  static String workManagerName = "newWM";
  static Path pathToChangeTargetYaml = null;
  static Path pathToAddClusterYaml = null;
  static Path pathToWmYaml = null;
  static final String configMapName = "dynamicupdate-test-configmap";
  static LoggingFacade logger = null;
  static String configmapcomponentname = "comp-" + configMapName;

  /**
   *
   * Create mii WebLogic domain resource.
   *
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the JUnit engine parameter resolution
   *             mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(1) List<String> namespaces) throws ApiException {
    logger = getLogger();
    logger.info("Getting unique namespace for Domain");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    domainNamespace = namespaces.get(0);
    setLabelToNamespace(Arrays.asList(domainNamespace));
    

    // write sparse yaml to change target to file
    pathToChangeTargetYaml = Paths.get(WORK_DIR + "/changetarget.yaml");
    String yamlToChangeTarget = "appDeployments:\n"
        + "  Application:\n"
        + "    myear:\n"
        + "      Target: 'cluster-1,admin-server'";

    assertDoesNotThrow(() -> Files.write(pathToChangeTargetYaml, yamlToChangeTarget.getBytes()));

    // write sparse yaml to file
    pathToAddClusterYaml = Paths.get(WORK_DIR + "/addcluster.yaml");
    String yamlToAddCluster = "topology:\n"
        + "    Cluster:\n"
        + "        \"cluster-2\":\n"
        + "            DynamicServers:\n"
        + "                ServerTemplate:  \"cluster-2-template\"\n"
        + "                ServerNamePrefix: \"dynamic-server\"\n"
        + "                DynamicClusterSize: 4\n"
        + "                MinDynamicClusterSize: 2\n"
        + "                MaxDynamicClusterSize: 4\n"
        + "                CalculatedListenPorts: false\n"
        + "    ServerTemplate:\n"
        + "        \"cluster-2-template\":\n"
        + "            Cluster: \"cluster-2\"\n"
        + "            ListenPort : 8001";

    assertDoesNotThrow(() -> Files.write(pathToAddClusterYaml, yamlToAddCluster.getBytes()));
    
    String configmap = "apiVersion: core.oam.dev/v1alpha2\n"
        + "kind: Component\n"
        + "metadata:\n"
        + "  name: " + configMapName + "\n"
        + "  namespace: " + domainNamespace + "\n"
        + "spec:\n"
        + "  workload:\n"
        + "    apiVersion: v1\n"
        + "    kind: ConfigMap\n"
        + "    metadata:\n"
        + "      labels:\n"
        + "        weblogic.domainUID: " + domainUid + "\n"
        + "      name: " + configMapName + "\n"
        + "      namespace: " + domainNamespace + "\n"
        + "    data:\n"
        + "      model.config.wm.yaml: |\n"
        + "        resources:\n"
        + "          SelfTuning:\n"
        + "            WorkManager:\n"
        + "              newWM:\n"
        + "                Target: 'cluster-1'\n"
        + "                MinThreadsConstraint: 'SampleMinThreads'\n"
        + "                MaxThreadsConstraint: 'SampleMaxThreads'\n"
        + "            MinThreadsConstraint:\n"
        + "              SampleMinThreads:\n"
        + "                Count: 1\n"
        + "            MaxThreadsConstraint:\n"
        + "              SampleMaxThreads:\n"
        + "                Count: 10";
    String addcluster = "apiVersion: core.oam.dev/v1alpha2\n"
        + "kind: Component\n"
        + "metadata:\n"
        + "  name: " + configmapcomponentname + "\n"
        + "  namespace: " + domainNamespace + "\n"
        + "spec:\n"
        + "  workload:\n"
        + "    apiVersion: v1\n"
        + "    kind: ConfigMap\n"
        + "    metadata:\n"
        + "      labels:\n"
        + "        weblogic.domainUID: " + domainUid + "\n"
        + "      name: " + configMapName + "\n"
        + "    data:\n"
        + "      wdt_cluster.yaml: |\n"
        + "        topology:\n"
        + "          Cluster:\n"
        + "            \"Cluster2\":\n"
        + "              DynamicServers:\n"
        + "                ServerTemplate: \"Cluster2Template\"\n"
        + "                ServerNamePrefix: \"ManagedServer\"\n"
        + "                DynamicClusterSize: 5\n"
        + "                MaxDynamicClusterSize: 5\n"
        + "                CalculatedListenPorts: false\n"
        + "          ServerTemplate:\n"
        + "            \"Cluster2Template\":\n"
        + "              Cluster: \"Cluster2\"\n"
        + "              ListenPort: 9001\n";
    pathToWmYaml = Paths.get(WORK_DIR + "/wm.yaml");
    assertDoesNotThrow(() -> Files.write(pathToWmYaml, addcluster.getBytes()));
    createVzMiiDomain();
  }

  /**
   * Create a configmap containing both the model yaml, and a sparse model file to add a new work manager, a min threads
   * constraint, and a max threads constraint Patch the domain resource with the configmap. Update the introspect
   * version of the domain resource. Verify rolling restart of the domain by comparing PodCreationTimestamp before and
   * after rolling restart. Verify new work manager is configured.
   */
  @Test
  @Order(1)
  @DisplayName("Add a work manager to a model-in-image domain using dynamic update")
  @Tag("gate")
  @Tag("crio")
  void testMiiAddWorkManager() throws ApiException {

    // This test uses the WebLogic domain created in BeforeAll method
    // BeforeEach method ensures that the server pods are running
    LinkedHashMap<String, OffsetDateTime> pods = new LinkedHashMap<>();

    // get the creation time of the admin server pod before patching
    pods.put(adminServerPodName,
        getPodCreationTime(domainNamespace, adminServerPodName));
    // get the creation time of the managed server pods before patching
    for (int i = 1; i <= replicaCount; i++) {
      pods.put(managedServerPrefix + i,
          getPodCreationTime(domainNamespace, managedServerPrefix + i));
    }

    List<String> modelFiles = Arrays.asList(MODEL_DIR + "/model.config.wm.yaml");
    recreateVzConfigmapComponent(configmapcomponentname, modelFiles, domainNamespace);
    
    String introspectVersion = patchDomainResourceWithNewIntrospectVersion(domainUid, domainNamespace);

    assertDoesNotThrow(() -> TimeUnit.MINUTES.sleep(5));
    //verifyIntrospectorRuns(domainUid, domainNamespace);

    String serverName = MANAGED_SERVER_NAME_BASE + "1";
    String uri = "/management/weblogic/latest/domainRuntime/serverRuntimes/"
        + serverName
        + "/applicationRuntimes/" + MII_BASIC_APP_DEPLOYMENT_NAME
        + "/workManagerRuntimes/" + workManagerName;

    // check configuration for JMS
    testUntil(
        () -> checkSystemResourceConfiguration(domainNamespace, uri, "200"),
        logger,
        "Checking for " + workManagerName + " in workManagerRuntimes exists");
    logger.info("Found the " + workManagerName + " configuration");

    verifyPodsNotRolled(domainNamespace, pods);

    verifyPodIntrospectVersionUpdated(pods.keySet(), introspectVersion, domainNamespace);
  }

  /**
   * Recreate configmap containing new cluster config. Patch the domain resource with the configmap. Update the
   * introspect version of the domain resource. Wait for introspector to complete Verify servers in the newly added
   * cluster are started and other servers are not rolled.
   */
  @Test
  @Disabled
  @Order(2)
  @DisplayName("Add cluster in MII domain using mii dynamic update")
  void testMiiAddCluster() {
    // This test uses the WebLogic domain created in BeforeAll method
    // BeforeEach method ensures that the server pods are running

    LinkedHashMap<String, OffsetDateTime> pods = new LinkedHashMap<>();

    // get the creation time of the admin server pod before patching
    OffsetDateTime adminPodCreationTime = getPodCreationTime(domainNamespace, adminServerPodName);
    pods.put(adminServerPodName, getPodCreationTime(domainNamespace, adminServerPodName));
    // get the creation time of the managed server pods before patching
    for (int i = 1; i <= replicaCount; i++) {
      pods.put(managedServerPrefix + i,
          getPodCreationTime(domainNamespace, managedServerPrefix + i));
    }

    // Replace contents of an existing configMap with cm config and application target as
    // there are issues with removing them, WDT-535
    replaceConfigMapWithModelFiles(MiiDynamicUpdateHelper.configMapName, domainUid, domainNamespace,
        Arrays.asList(MODEL_DIR + "/model.config.wm.yaml",
            pathToAddClusterYaml.toString()), withStandardRetryPolicy);

    // change replica to have the servers running in the newly added cluster
    assertTrue(patchDomainResourceWithNewReplicaCountAtSpecLevel(
        domainUid, domainNamespace, replicaCount),
        "failed to patch the replicas at spec level");

    // Patch a running domain with introspectVersion.
    String introspectVersion = patchDomainResourceWithNewIntrospectVersion(domainUid, domainNamespace);

    // Verifying introspector pod is created, runs and deleted
    verifyIntrospectorRuns(domainUid, domainNamespace);

    // check the servers are started in newly added cluster and the server services and pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed server services and pods are created in namespace {0}",
          domainNamespace);
      checkPodReadyAndServiceExists(domainUid + "-dynamic-server" + i, domainUid, domainNamespace);
    }

    verifyPodsNotRolled(domainNamespace, pods);

    verifyPodIntrospectVersionUpdated(pods.keySet(), introspectVersion, domainNamespace);

  }

  private static void createVzMiiDomain() {

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    createTestRepoSecret(domainNamespace);

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    createSecretWithUsernamePassword(adminSecretName, domainNamespace,
        ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    createSecretWithUsernamePassword(encryptionSecretName, domainNamespace,
        "weblogicenc", "weblogicenc");

    // create cluster object
    String clusterName = "cluster-1";
    
    createVzConfigmapComponent(Collections.emptyList());
    //createVzConfigmapComponent(Arrays.asList(MODEL_DIR + "/model.config.wm.yaml"));
    String command = KUBERNETES_CLI + " apply -f " + pathToWmYaml;
    logger.info("command {0} ", command);
    assertDoesNotThrow(() -> logger.info(Files.readString(pathToWmYaml)));
    ExecResult result = assertDoesNotThrow(() -> exec(command, true));
    logger.info(String.valueOf(result.exitValue()));
    logger.info(result.stdout());
    logger.info(result.stderr());
    assertEquals(0, result.exitValue(), "Failed to create config map");

    testUntil(new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        try {
          return Kubernetes.listComponents(domainNamespace).getItems().stream()
              .anyMatch(component -> component.getMetadata().getName().equals(configmapcomponentname));
        } catch (ApiException ex) {
          logger.warning(ex.getResponseBody());
        }
        return false;
      }
    },
        logger,
        "Checking for " + configMapName + " in namespace " + domainNamespace + " exists");

    assertDoesNotThrow(() -> logger.info(Yaml.dump(Kubernetes.listComponents(domainNamespace))));

    
    DomainResource domain = createDomainResource(domainUid, domainNamespace,
        MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG,
        adminSecretName, new String[]{TEST_IMAGES_REPO_SECRET_NAME},
        encryptionSecretName, replicaCount, Arrays.asList(clusterName));
    logger.info(Yaml.dump(domain));
    domain.spec().configuration().model().setConfigMap(configMapName);
    logger.info(Yaml.dump(domain));

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
            .components(Arrays.asList(
                new Components()
                    .componentName(domainUid)
                    .traits(Arrays.asList(new IngressTraits()
                        .trait(new IngressTrait()
                            .apiVersion("oam.verrazzano.io/v1alpha1")
                            .kind("IngressTrait")
                            .metadata(new V1ObjectMeta()
                                .name("mydomain-ingress")
                                .namespace(domainNamespace))
                            .spec(new IngressTraitSpec()
                                .ingressRules(Arrays.asList(
                                    new IngressRule()
                                        .paths(Arrays.asList(new oracle.verrazzano.weblogic.Path()
                                            .path("/console")
                                            .pathType("Prefix")))
                                        .destination(new Destination()
                                            .host(adminServerPodName)
                                            .port(7001)),
                                    new IngressRule()
                                        .paths(Arrays.asList(new oracle.verrazzano.weblogic.Path()
                                            .path("/management")
                                            .pathType("Prefix")))
                                        .destination(new Destination()
                                            .host(adminServerPodName)
                                            .port(7001)),
                                    new IngressRule()
                                        .paths(Arrays.asList(new oracle.verrazzano.weblogic.Path()
                                            .path("/sample-war")
                                            .pathType("Prefix")))
                                        .destination(new Destination()
                                            .host(domainUid + "-cluster-" + clusterName)
                                            .port(8001)))))))),
                new Components()
                    .componentName(configmapcomponentname))));

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

    // verify sample running in cluster is accessible through istio/loadbalancer
    message = "Hello World, you have reached server managed-server";
    String appUrl = "https://" + host + "/sample-war/index.jsp --resolve " + host + ":443:" + address;
    assertTrue(verifyVzApplicationAccess(appUrl, message), "Failed to get access to sample application");

  }

  private static boolean checkSystemResourceConfiguration(String namespace, String uri, String expectedStatusCode) {
    // get istio gateway host and loadbalancer address
    String host = getIstioHost(namespace);
    String address = getLoadbalancerAddress();

    StringBuffer curlString = new StringBuffer("status=$(curl -k --user ");
    curlString.append(ADMIN_USERNAME_DEFAULT + ":" + ADMIN_PASSWORD_DEFAULT)
        .append(" https://" + host)
        .append(uri)
        .append("/ --resolve " + host + ":443:" + address)
        .append(" --silent --show-error ")
        .append(" -o /dev/null ")
        .append(" -w %{http_code});")
        .append("echo ${status}");
    logger.info("checkSystemResource: curl command {0}", new String(curlString));
    return Command
        .withParams(new CommandParams()
            .command(curlString.toString()))
        .executeAndVerify(expectedStatusCode);
  }
  
  static void createVzConfigmapComponent(List<String> modelFiles) {

    Map<String, String> labels = new HashMap<>();
    labels.put("weblogic.domainUID", domainUid);
    assertNotNull(configmapcomponentname, "ConfigMap component name cannot be null");
    logger.info("Create ConfigMap component {0} that contains model files {1}",
        configmapcomponentname, modelFiles);
    Map<String, String> data = new HashMap<>();
    for (String modelFile : modelFiles) {
      ConfigMapUtils.addModelFile(data, modelFile);
    }

    Component component = new Component()
        .apiVersion("core.oam.dev/v1alpha2")
        .kind("Component")
        .metadata(new V1ObjectMeta()
            .name(configmapcomponentname)
            .namespace(domainNamespace))
        .spec(new ComponentSpec()
            .workLoad(new Workload()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(new V1ObjectMeta()
                    .labels(labels)
                    .name(configMapName))
                .data(data)));
    logger.info("Deploying configmap component");
    logger.info(Yaml.dump(component));
    assertDoesNotThrow(() -> createComponent(component));
    assertDoesNotThrow(() -> logger.info(Yaml.dump(Kubernetes.listComponents(domainNamespace))));
  }

  static void deleteVzConfigmapComponent(String name, String namespace) throws ApiException {
    assertTrue(deleteComponent(configmapcomponentname, domainNamespace));
    // check configuration for JMS
    testUntil(() -> {
      try {
        return !Kubernetes.listComponents(namespace).getItems().stream()
            .anyMatch(component -> component.getMetadata().getName().equals(name));
      } catch (ApiException ex) {
        logger.warning(ex.getResponseBody());
      }
      return false;
    },
        logger,
        "Checking for " + name + " in namespace " + namespace + " exists");
    logger.info("Component " + name + " deleted");
  }
  
  static void recreateVzConfigmapComponent(String name, List<String> modelFiles, String namespace) throws ApiException {

    deleteVzConfigmapComponent(name, namespace);
    testUntil(() -> {
      return !Kubernetes.listComponents(namespace).getItems().stream()
          .anyMatch(component -> component.getMetadata().getName().equals(configmapcomponentname));
    },
        logger,
        "Checking for " + name + " in namespace " + namespace + " doesn't exists");
    createVzConfigmapComponent(modelFiles);
    testUntil(() -> {
      return Kubernetes.listComponents(namespace).getItems().stream()
          .anyMatch(component -> component.getMetadata().getName().equals(configmapcomponentname));
    },
        logger,
        "Checking for " + name + " in namespace " + namespace + " exists");
  }

}
