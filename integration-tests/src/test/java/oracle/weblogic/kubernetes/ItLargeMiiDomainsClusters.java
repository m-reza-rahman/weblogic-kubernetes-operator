// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.util.Arrays;
import java.util.List;

import oracle.weblogic.domain.DomainResource;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createMiiDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNonEmptySystemProperty;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Create large number of domains, clusters and servers.
 */
@DisplayName("Test to create large number of domains, clusters and servers.")
@IntegrationTest
class ItLargeMiiDomainsClusters {
  private static String opNamespace = null;
  private static List<String> domainNamespaces;
  private static final String baseDomainUid = "domain";
  private static final String baseClusterName = "cluster-";
  private static int numOfDomains = Integer.valueOf(getNonEmptySystemProperty("NUMBER_OF_DOMAINS", "2"));
  private static int numOfClusters = Integer.valueOf(getNonEmptySystemProperty("NUMBER_OF_CLUSTERS", "1"));
  private String miiImage = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;
  private static String adminServerPrefix = "-" + ADMIN_SERVER_NAME_BASE;
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
      List<String> clusterNameList = Arrays.asList("cluster-1");
      /* for (int j = 0; i < numOfClusters; j++) {
        clusterNameList.add(baseClusterName + (j + 1));
      } */
      String adminServerPodName = domainUid + adminServerPrefix;
      String managedServerPrefix = domainUid + "-" + MANAGED_SERVER_NAME_BASE;
      // create domain with cluster resource
      DomainResource domain = createMiiDomainAndVerify(domainNamespaces.get(i), domainUid, miiImage,
          adminServerPodName, managedServerPrefix, replicaCount, clusterNameList,
          false, null);
      assertNotNull(domain, "Can't create and verify domain");
    }

  }
}
