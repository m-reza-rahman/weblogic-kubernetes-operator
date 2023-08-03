// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.DomainResource;
import oracle.weblogic.domain.Model;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.MII_ADMINONLY_WDT_MODEL_FILE;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.TEST_IMAGES_REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.actions.TestActions.createConfigMap;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createDomainResource;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNonEmptySystemProperty;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createMiiImageAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createTestRepoSecret;
import static oracle.weblogic.kubernetes.utils.ImageUtils.imageRepoLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.PodUtils.checkPodReady;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Create large number of domains, clusters and servers.
 */
@DisplayName("Test to create large number of domains, clusters and servers.")
@IntegrationTest
class ItLargeMiiDomainsClusters {
  private static String opNamespace = null;
  private static String miiAdminOnlyImage;
  private static List<String> domainNamespaces;
  private static final String baseDomainUid = "domain";
  private static final String baseClusterName = "cluster-";
  private static String adminServerPrefix = "-" + ADMIN_SERVER_NAME_BASE;
  private static int numOfDomains = Integer.valueOf(getNonEmptySystemProperty("NUMBER_OF_DOMAINS", "2"));
  private static int numOfClusters = Integer.valueOf(getNonEmptySystemProperty("NUMBER_OF_CLUSTERS", "2"));
  private String miiImage = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;
  private static String opServiceAccount = opNamespace + "-sa";

  private static LoggingFacade logger = null;
  final int replicaCount = 2;

  /**
   * Install Operator.
   *
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   *                   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(25) List<String> namespaces) {
    logger = getLogger();

    // get a new unique opNamespace
    logger.info("Assign unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    logger.info("Assign unique namespaces for Domains");
    domainNamespaces = namespaces.subList(1, numOfDomains + 1);

    // build MII admin only image
    logger.info("Build/Check mii-adminonly image with tag {0}", MII_BASIC_IMAGE_TAG);
    miiAdminOnlyImage =
        createMiiImageAndVerify("mii-adminonly-image", MII_ADMINONLY_WDT_MODEL_FILE, null,
            null, null);

    // repo login and push image to registry if necessary
    imageRepoLoginAndPushImageToRegistry(miiAdminOnlyImage);

    // install and verify operator
    installAndVerifyOperator(opNamespace, namespaces.subList(1, numOfDomains + 1).toArray(new String[0]));

  }

  /**
   * Create given number of domains with clusters.
   */
  @Test
  @DisplayName("Create n number of domains/clusters")
  void testCreateNDomainsNClusters() {

    for (int i = 0; i < numOfDomains; i++) {
      String domainUid = baseDomainUid + (i + 1);
      String configMapName = domainUid + "-configmap";
      List<String> clusterNameList = new ArrayList<>();
      for (int j = 1; j <= numOfClusters; j++) {
        clusterNameList.add(baseClusterName + j);
      }
      // create model with all clusters
      createModelConfigMap(domainUid, configMapName, domainNamespaces.get(i));

      // Create the repo secret to pull the image
      // this secret is used only for non-kind cluster
      createTestRepoSecret(domainNamespaces.get(i));

      // create secret for admin credentials
      logger.info("Create secret for admin credentials");
      String adminSecretName = "weblogic-credentials";
      createSecretWithUsernamePassword(adminSecretName, domainNamespaces.get(i),
          ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

      // create encryption secret
      logger.info("Create encryption secret");
      String encryptionSecretName = "encryptionsecret";
      createSecretWithUsernamePassword(encryptionSecretName, domainNamespaces.get(i),
          "weblogicenc", "weblogicenc");

      // create and deploy domain resource
      DomainResource domain = createDomainResource(domainUid, domainNamespaces.get(i),
          miiAdminOnlyImage,
          adminSecretName, new String[]{TEST_IMAGES_REPO_SECRET_NAME},
          encryptionSecretName, replicaCount, clusterNameList);
      domain.getSpec().configuration(new Configuration()
          .model(new Model()
              .configMap(configMapName)
              .runtimeEncryptionSecret(encryptionSecretName)));

      logger.info("Creating Domain Resource {0} in namespace {1} using image {2}",
          domainUid, domainNamespaces.get(i),
          miiAdminOnlyImage);
      createDomainAndVerify(domain, domainNamespaces.get(i));

      String adminServerPodName = domainUid + adminServerPrefix;
      // check admin server pod is ready
      logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
          adminServerPodName, domainNamespaces.get(i));
      checkPodReady(adminServerPodName, domainUid, domainNamespaces.get(i));

      // check managed server pods are ready in all clusters in the domain
      for (int j = 1; j <= numOfClusters; j++) {
        String managedServerPrefix = "c" + j + "-managed-server";
        // check managed server pods are ready
        for (int k = 1; k <= replicaCount; k++) {
          logger.info("Wait for managed server pod {0} to be ready in namespace {1}",
              managedServerPrefix + k, domainNamespaces.get(i));
          checkPodReady(managedServerPrefix + k, domainUid, domainNamespaces.get(i));
        }
      }

    }

  }

  private static void createModelConfigMap(
      String domainid, String cfgMapName, String domainNamespace) {
    String yamlString = "topology:\n"
        + "  Cluster:\n";
    String clusterYamlString = "";
    String serverTemplateYamlString = "";
    for (int i = 1; i <= numOfClusters; i++) {
      clusterYamlString = clusterYamlString
          + "    'cluster-" + i + "':\n"
          + "       DynamicServers: \n"
          + "         ServerTemplate: 'cluster-" + i + "-template' \n"
          + "         ServerNamePrefix: 'c" + i + "-managed-server' \n"
          + "         DynamicClusterSize: 5 \n"
          + "         MaxDynamicClusterSize: 5 \n"
          + "         CalculatedListenPorts: false \n";
      serverTemplateYamlString = serverTemplateYamlString
          + "    'cluster-" + i + "-template':\n"
          + "       Cluster: 'cluster-" + i + "' \n"
          + "       ListenPort : 8001 \n";
    }
    yamlString = clusterYamlString
        + "  ServerTemplate:\n"
        + serverTemplateYamlString;
    logger.info("Yamlstring " + yamlString);
    Map<String, String> labels = new HashMap<>();
    labels.put("weblogic.domainUid", domainid);
    Map<String, String> data = new HashMap<>();
    data.put("model.cluster.yaml", yamlString);

    V1ConfigMap configMap = new V1ConfigMap()
        .data(data)
        .metadata(new V1ObjectMeta()
            .labels(labels)
            .name(cfgMapName)
            .namespace(domainNamespace));
    boolean cmCreated = assertDoesNotThrow(() -> createConfigMap(configMap),
        String.format("Can't create ConfigMap %s", cfgMapName));
    assertTrue(cmCreated, String.format("createConfigMap failed %s", cfgMapName));
  }
}
