// Copyright (c) 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.ClusterResource;
import oracle.weblogic.domain.DomainResource;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.BuildApplication;
import oracle.weblogic.kubernetes.utils.ExecCommand;
import oracle.weblogic.kubernetes.utils.ExecResult;
import oracle.weblogic.kubernetes.utils.OracleHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.IMAGE_PULL_POLICY;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.KUBERNETES_CLI;
import static oracle.weblogic.kubernetes.TestConstants.OKE_CLUSTER;
import static oracle.weblogic.kubernetes.TestConstants.TRAEFIK_INGRESS_HTTP_HOSTPORT;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_TO_USE_IN_SPEC;
import static oracle.weblogic.kubernetes.actions.ActionConstants.APP_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ITTESTS_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.getDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.getServicePort;
import static oracle.weblogic.kubernetes.actions.impl.Domain.patchDomainCustomResource;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.verifyRollingRestartOccurred;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.verifyAdminServerRESTAccess;
import static oracle.weblogic.kubernetes.utils.ClusterUtils.createClusterAndVerify;
import static oracle.weblogic.kubernetes.utils.ClusterUtils.createClusterResource;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createIngressHostRouting;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.formatIPv6Host;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getHostAndPort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNextFreePort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getUniqueName;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.ConfigMapUtils.createConfigMapForDomainCreation;
import static oracle.weblogic.kubernetes.utils.DeployUtil.deployUsingWlst;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.JobUtils.createDomainJob;
import static oracle.weblogic.kubernetes.utils.OKDUtils.createRouteForOKD;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.PersistentVolumeUtils.createPV;
import static oracle.weblogic.kubernetes.utils.PersistentVolumeUtils.createPVC;
import static oracle.weblogic.kubernetes.utils.PodUtils.getExternalServicePodName;
import static oracle.weblogic.kubernetes.utils.PodUtils.getPodCreationTime;
import static oracle.weblogic.kubernetes.utils.PodUtils.setPodAntiAffinity;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretsForImageRepos;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests related to introspectVersion attribute.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Verify the introspectVersion runs the introspector")
@IntegrationTest
@Tag("olcne-srg")
@Tag("kind-parallel")
@Tag("okd-wls-mrg")
@Tag("oke-sequential1")
@Tag("oke-arm")
class ItRestart {

  private static String opNamespace = null;
  private static String introDomainNamespace = null;
  private static String miiDomainNamespace = null;
  private static final String BADMII_IMAGE = "bad-modelfile-mii-image";
  private static String badMiiImage;
  private static final String BADMII_MODEL_FILE = "mii-bad-model-file.yaml";

  private static final String domainUid = "myintrodomain";
  private static final String cluster1Name = "mycluster";
  private static final String adminServerName = "admin-server";
  private static final String adminServerPodName = domainUid + "-" + adminServerName;
  private static final String cluster1ManagedServerNameBase = "managed-server";
  private static final String cluster1ManagedServerPodNamePrefix = domainUid + "-" + cluster1ManagedServerNameBase;

  private static int cluster1ReplicaCount = 2;
  private static final int t3ChannelPort = getNextFreePort();

  private static final String pvName = getUniqueName(domainUid + "-pv-");
  private static final String pvcName = getUniqueName(domainUid + "-pvc-");

  private static final String wlSecretName = "weblogic-credentials";
  private static String wlsUserName = ADMIN_USERNAME_DEFAULT;
  private static String wlsPassword = ADMIN_PASSWORD_DEFAULT;

  private static String adminSvcExtHost = null;

  private Map<String, OffsetDateTime> cl2podsWithTimeStamps = null;

  private static final String INTROSPECT_DOMAIN_SCRIPT = "introspectDomain.sh";
  private static final Path samplePath = Paths.get(ITTESTS_DIR, "../kubernetes/samples");
  private static final Path tempSamplePath = Paths.get(WORK_DIR, "intros-sample-testing");
  private static final Path domainLifecycleSamplePath = Paths.get(samplePath + "/scripts/domain-lifecycle");

  private static Path clusterViewAppPath;
  private static LoggingFacade logger = null;
  private static final int managedServerPort = 7100;
  private static int adminPort = 7001;
  private static String hostHeader;

  /**
   * Assigns unique namespaces for operator and domains.
   * Pull WebLogic image if running tests in Kind cluster.
   * Installs operator.
   *
   * @param namespaces injected by JUnit
   */
  @BeforeAll
  public static void initAll(@Namespaces(2) List<String> namespaces) {
    logger = getLogger();
    logger.info("Assign a unique namespace for operator");
    assertNotNull(namespaces.get(0), "Namespace is null");
    opNamespace = namespaces.get(0);
    
    logger.info("Assign a unique namespace for Introspect Version WebLogic domain");
    assertNotNull(namespaces.get(1), "Namespace is null");
    introDomainNamespace = namespaces.get(1);
    
    // install operator and verify its running in ready state
    installAndVerifyOperator(opNamespace, introDomainNamespace);

    // build the clusterview application
    Path targetDir = Paths.get(WORK_DIR,
         ItRestart.class.getName() + "/clusterviewapp");
    Path distDir = BuildApplication.buildApplication(Paths.get(APP_DIR, "clusterview"), null, null,
        "dist", introDomainNamespace, targetDir);
    assertTrue(Paths.get(distDir.toString(),
        "clusterview.war").toFile().exists(),
        "Application archive is not available");
    clusterViewAppPath = Paths.get(distDir.toString(), "clusterview.war");

    createDomain();
  }

  /**
   * Test domain restart.
   */
  @Test
  @DisplayName("Test restarting domain repeatedly")
  void testDomainRestart() {
    for (int count = 0; count < 10; count++) {
      logger.info("Restarting the domain START, count {0}", count);
      // get the pod creation time stamps
      Map<String, OffsetDateTime> pods = new LinkedHashMap<>();
      // get the creation time of the admin server pod before patching
      OffsetDateTime adminPodCreationTime = getPodCreationTime(introDomainNamespace, adminServerPodName);
      pods.put(adminServerPodName, adminPodCreationTime);
      // get the creation time of the managed server pods before patching
      for (int i = 1; i <= cluster1ReplicaCount; i++) {
        pods.put(cluster1ManagedServerPodNamePrefix + i,
            getPodCreationTime(introDomainNamespace, cluster1ManagedServerPodNamePrefix + i));
      }

      String oldVersion = assertDoesNotThrow(()
          -> getDomainCustomResource(domainUid, introDomainNamespace).getSpec().getRestartVersion());
      int newVersion = oldVersion == null ? 1 : Integer.valueOf(oldVersion) + 1;

      logger.info("patch the domain resource with restartVersion");
      String patchStr
          = "["
          + "{\"op\": \"add\", \"path\": \"/spec/restartVersion\", "
          + "\"value\": \"" + newVersion + "\"}"
          + "]";
      logger.info("Updating domain configuration using patch string: {0}\n", patchStr);
      V1Patch patch = new V1Patch(patchStr);
      assertTrue(patchDomainCustomResource(domainUid, introDomainNamespace, patch, V1Patch.PATCH_FORMAT_JSON_PATCH),
          "Failed to patch domain");
      
      //verify the pods are restarted
      verifyRollingRestartOccurred(pods, 1, introDomainNamespace);

      // verify the admin server service and pod created
      checkPodReadyAndServiceExists(adminServerPodName, domainUid, introDomainNamespace);

      // verify managed server services and pods are created
      for (int i = 1; i <= cluster1ReplicaCount; i++) {
        logger.info("Checking managed server service and pod {0} is created in namespace {1}",
            cluster1ManagedServerPodNamePrefix + i, introDomainNamespace);
        checkPodReadyAndServiceExists(cluster1ManagedServerPodNamePrefix + i, domainUid, introDomainNamespace);
      }

      List<String> managedServerNames = new ArrayList<>();
      for (int i = 1; i <= cluster1ReplicaCount; i++) {
        managedServerNames.add(cluster1ManagedServerNameBase + i);
      }

      //verify admin server accessibility and the health of cluster members
      verifyMemberHealth(adminServerPodName, managedServerNames, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);
      logger.info("Restarting the domain DONE, count {0}", count);
    }
  }

  
  private static void createDomain() {    
    String uniquePath = "/shared/" + introDomainNamespace + "/domains";

    // create WebLogic domain credential secret
    createSecretWithUsernamePassword(wlSecretName, introDomainNamespace,
        wlsUserName, wlsPassword);
    createPV(pvName, domainUid, ItRestart.class.getSimpleName());
    createPVC(pvName, pvcName, domainUid, introDomainNamespace);

    // create a temporary WebLogic domain property file
    File domainPropertiesFile = assertDoesNotThrow(() ->
            File.createTempFile("domain", "properties"),
        "Failed to create domain properties file");
    Properties p = new Properties();
    p.setProperty("domain_path", uniquePath);
    p.setProperty("domain_name", domainUid);
    p.setProperty("cluster_name", cluster1Name);
    p.setProperty("admin_server_name", adminServerName);
    p.setProperty("managed_server_port", Integer.toString(managedServerPort));
    p.setProperty("admin_server_port", "7001");
    p.setProperty("admin_username", wlsUserName);
    p.setProperty("admin_password", wlsPassword);
    p.setProperty("admin_t3_public_address", K8S_NODEPORT_HOST);
    p.setProperty("admin_t3_channel_port", Integer.toString(t3ChannelPort));
    p.setProperty("number_of_ms", "2"); // maximum number of servers in cluster
    p.setProperty("managed_server_name_base", cluster1ManagedServerNameBase);
    p.setProperty("domain_logs", uniquePath + "/logs/" + domainUid);
    p.setProperty("production_mode_enabled", "true");
    assertDoesNotThrow(() ->
            p.store(new FileOutputStream(domainPropertiesFile), "domain properties file"),
        "Failed to write domain properties file");

    // WLST script for creating domain
    Path wlstScript = Paths.get(RESOURCE_DIR, "python-scripts", "wlst-create-domain-onpv.py");

    // create configmap and domain on persistent volume using the WLST script and property file
    createDomainOnPVUsingWlst(wlstScript, domainPropertiesFile.toPath(),
        pvName, pvcName, introDomainNamespace);
    
    // create cluster object
    String clusterResName = domainUid + "-" + cluster1Name;
    ClusterResource cluster = createClusterResource(clusterResName,
        cluster1Name, introDomainNamespace, cluster1ReplicaCount);

    logger.info("Creating cluster resource {0} in namespace {1}",clusterResName, introDomainNamespace);
    createClusterAndVerify(cluster);    

    // create a domain custom resource configuration object
    logger.info("Creating domain custom resource");
    DomainResource domain = new DomainResource()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(introDomainNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHome(uniquePath + "/" + domainUid) // point to domain home in pv
            .domainHomeSourceType("PersistentVolume") // set the domain home source type as pv
            .image(WEBLOGIC_IMAGE_TO_USE_IN_SPEC)
            .imagePullPolicy(IMAGE_PULL_POLICY)
            .webLogicCredentialsSecret(new V1LocalObjectReference()
                .name(wlSecretName))
            .includeServerOutInPodLog(true)
            .logHomeEnabled(Boolean.TRUE)
            .logHome(uniquePath + "/logs/" + domainUid)
            .dataHome("")
            .serverStartPolicy("IfNeeded")
            .serverPod(new ServerPod() //serverpod
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom "))
                .addVolumesItem(new V1Volume()
                    .name(pvName)
                    .persistentVolumeClaim(new V1PersistentVolumeClaimVolumeSource()
                        .claimName(pvcName)))
                .addVolumeMountsItem(new V1VolumeMount()
                    .mountPath("/shared")
                    .name(pvName)))
            .adminServer(new AdminServer() //admin server
                .adminService(new AdminService()
                    .addChannelsItem(new Channel()
                        .channelName("default")
                        .nodePort(getNextFreePort())))));

    // create secrets
    List<V1LocalObjectReference> secrets = new ArrayList<>();
    for (String secret : createSecretsForImageRepos(introDomainNamespace)) {
      secrets.add(new V1LocalObjectReference().name(secret));
    }
    domain.spec().setImagePullSecrets(secrets);
    // set cluster references
    domain.getSpec().withCluster(new V1LocalObjectReference().name(clusterResName));

    setPodAntiAffinity(domain);
    // verify the domain custom resource is created
    createDomainAndVerify(domain, introDomainNamespace);

    // verify the admin server service and pod created
    checkPodReadyAndServiceExists(adminServerPodName, domainUid, introDomainNamespace);

    // verify managed server services created
    for (int i = 1; i <= cluster1ReplicaCount; i++) {
      logger.info("Checking managed server service and pod {0} is created in namespace {1}",
          cluster1ManagedServerPodNamePrefix + i, introDomainNamespace);
      checkPodReadyAndServiceExists(cluster1ManagedServerPodNamePrefix + i, domainUid, introDomainNamespace);
    }

    adminSvcExtHost = createRouteForOKD(getExternalServicePodName(adminServerPodName), introDomainNamespace);
    logger.info("admin svc host = {0}", adminSvcExtHost);

    // deploy application and verify all servers functions normally
    logger.info("Getting port for default channel");
    int defaultChannelPort = assertDoesNotThrow(()
        -> getServicePort(introDomainNamespace, getExternalServicePodName(adminServerPodName), "default"),
        "Getting admin server default port failed");
    logger.info("default channel port: {0}", defaultChannelPort);
    assertNotEquals(-1, defaultChannelPort, "admin server defaultChannelPort is not valid");

    int serviceNodePort = assertDoesNotThrow(() ->
            getServiceNodePort(introDomainNamespace, getExternalServicePodName(adminServerPodName), "default"),
        "Getting admin server node port failed");
    logger.info("Admin Server default node port : {0}", serviceNodePort);
    assertNotEquals(-1, serviceNodePort, "admin server default node port is not valid");

    //deploy clusterview application
    logger.info("Deploying clusterview app {0} to cluster {1}", clusterViewAppPath, cluster1Name);

    assertDoesNotThrow(() -> deployUsingWlst(adminServerPodName,
        String.valueOf(adminPort),
        wlsUserName,
        wlsPassword,
        cluster1Name + "," + adminServerName,
        clusterViewAppPath,
        introDomainNamespace),"Deploying the application");

    List<String> managedServerNames = new ArrayList<>();
    for (int i = 1; i <= cluster1ReplicaCount; i++) {
      managedServerNames.add(cluster1ManagedServerNameBase + i);
    }
    if (TestConstants.KIND_CLUSTER
        && !TestConstants.WLSIMG_BUILDER.equals(TestConstants.WLSIMG_BUILDER_DEFAULT)) {
      hostHeader = createIngressHostRouting(introDomainNamespace, domainUid, adminServerName, adminPort);
      assertDoesNotThrow(() -> verifyAdminServerRESTAccess(formatIPv6Host(InetAddress.getLocalHost().getHostAddress()), 
          TRAEFIK_INGRESS_HTTP_HOSTPORT, false, hostHeader));
    }    

    //verify admin server accessibility and the health of cluster members
    verifyMemberHealth(adminServerPodName, managedServerNames, wlsUserName, wlsPassword);
  }


  /**
   * Create a WebLogic domain on a persistent volume by doing the following.
   * Create a configmap containing WLST script and property file.
   * Create a Kubernetes job to create domain on persistent volume.
   *
   * @param wlstScriptFile       python script to create domain
   * @param domainPropertiesFile properties file containing domain configuration
   * @param pvName               name of the persistent volume to create domain in
   * @param pvcName              name of the persistent volume claim
   * @param namespace            name of the domain namespace in which the job is created
   */
  private static void createDomainOnPVUsingWlst(Path wlstScriptFile, Path domainPropertiesFile,
                                         String pvName, String pvcName, String namespace) {
    logger.info("Preparing to run create domain job using WLST");

    List<Path> domainScriptFiles = new ArrayList<>();
    domainScriptFiles.add(wlstScriptFile);
    domainScriptFiles.add(domainPropertiesFile);

    logger.info("Creating a config map to hold domain creation scripts");
    String domainScriptConfigMapName = "create-domain-scripts-cm";
    assertDoesNotThrow(() -> createConfigMapForDomainCreation(domainScriptConfigMapName, 
        domainScriptFiles, namespace, ItRestart.class.getSimpleName()),
        "Create configmap for domain creation failed");

    // create a V1Container with specific scripts and properties for creating domain
    V1Container jobCreationContainer = new V1Container()
        .addCommandItem("/bin/sh")
        .addArgsItem("/u01/oracle/oracle_common/common/bin/wlst.sh")
        .addArgsItem("/u01/weblogic/" + wlstScriptFile.getFileName()) //wlst.sh script
        .addArgsItem("-skipWLSModuleScanning")
        .addArgsItem("-loadProperties")
        .addArgsItem("/u01/weblogic/" + domainPropertiesFile.getFileName()); //domain property file

    logger.info("Running a Kubernetes job to create the domain");
    createDomainJob(WEBLOGIC_IMAGE_TO_USE_IN_SPEC, pvName, pvcName, domainScriptConfigMapName,
        namespace, jobCreationContainer);

  }

  private static void verifyMemberHealth(String adminServerPodName, List<String> managedServerNames,
      String user, String code) {

    logger.info("Checking the health of servers in cluster");

    testUntil(() -> {
      if (OKE_CLUSTER) {
        // In internal OKE env, verifyMemberHealth in admin server pod
        int servicePort = getServicePort(introDomainNamespace, 
            getExternalServicePodName(adminServerPodName), "default");
        final String command = KUBERNETES_CLI + " exec -n "
            + introDomainNamespace + "  " + adminServerPodName + " -- curl http://"
            + adminServerPodName + ":"
            + servicePort + "/clusterview/ClusterViewServlet"
            + "\"?user=" + user
            + "&password=" + code + "\"";

        ExecResult result = null;
        try {
          result = ExecCommand.exec(command, true);
        } catch (IOException | InterruptedException ex) {
          logger.severe(ex.getMessage());
        }
        String response = result.stdout().trim();
        logger.info(response);
        logger.info(result.stderr());
        logger.info("{0}", result.exitValue());
        boolean health = true;
        for (String managedServer : managedServerNames) {
          health = health && response.contains(managedServer + ":HEALTH_OK");
          if (health) {
            logger.info(managedServer + " is healthy");
          } else {
            logger.info(managedServer + " health is not OK or server not found");
          }
        }
        return health;
      } else {
        // In non-internal OKE env, verifyMemberHealth using adminSvcExtHost by sending HTTP request from local VM

        // TEST, HERE
        String extSvcPodName = getExternalServicePodName(adminServerPodName);
        logger.info("**** adminServerPodName={0}", adminServerPodName);
        logger.info("**** extSvcPodName={0}", extSvcPodName);

        adminSvcExtHost = createRouteForOKD(extSvcPodName, introDomainNamespace);
        logger.info("**** adminSvcExtHost={0}", adminSvcExtHost);
        logger.info("admin svc host = {0}", adminSvcExtHost);

        logger.info("Getting node port for default channel");
        int serviceNodePort = assertDoesNotThrow(()
            -> getServiceNodePort(introDomainNamespace, getExternalServicePodName(adminServerPodName), "default"),
            "Getting admin server node port failed");
        String hostAndPort = getHostAndPort(adminSvcExtHost, serviceNodePort);
        
        Map<String, String> headers = null;
        if (TestConstants.KIND_CLUSTER
            && !TestConstants.WLSIMG_BUILDER.equals(TestConstants.WLSIMG_BUILDER_DEFAULT)) {
          hostAndPort = formatIPv6Host(InetAddress.getLocalHost().getHostAddress()) 
              + ":" + TRAEFIK_INGRESS_HTTP_HOSTPORT;
          headers = new HashMap<>();
          headers.put("host", hostHeader);
        }

        boolean ipv6 = K8S_NODEPORT_HOST.contains(":");
        String url = "http://" + hostAndPort
            + "/clusterview/ClusterViewServlet?user=" + user + "&password=" + code + "&ipv6=" + ipv6;
        HttpResponse<String> response;
        response = OracleHttpClient.get(url, headers, true);

        boolean health = true;
        for (String managedServer : managedServerNames) {
          health = health && response.body().contains(managedServer + ":HEALTH_OK");
          if (health) {
            logger.info(managedServer + " is healthy");
          } else {
            logger.info(managedServer + " health is not OK or server not found");
          }
        }
        return health;
      }
    },
        logger,
        "Verifying the health of all cluster members");
  }
  
}
