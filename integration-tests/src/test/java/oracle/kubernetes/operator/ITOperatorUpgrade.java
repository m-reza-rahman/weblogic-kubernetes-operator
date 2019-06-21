// Copyright 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.
package oracle.kubernetes.operator;

import static oracle.kubernetes.operator.BaseTest.DOMAININIMAGE_WLST_YAML;
import static oracle.kubernetes.operator.BaseTest.OPERATOR1_YAML;
import static oracle.kubernetes.operator.BaseTest.QUICKTEST;
import static oracle.kubernetes.operator.BaseTest.logger;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import oracle.kubernetes.operator.utils.Domain;
import oracle.kubernetes.operator.utils.ExecCommand;
import oracle.kubernetes.operator.utils.ExecResult;
import oracle.kubernetes.operator.utils.Operator;
import oracle.kubernetes.operator.utils.TestUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Simple JUnit test file used for testing Operator.
 *
 * <p>This test is used for testing Helm install for Operator(s)
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ITOperatorUpgrade extends BaseTest {

  private static final String OP_BASE_REL = "2.0";
  private static final String OP_NS = "weblogic-operator";
  private static final String OP_DEP_NAME = "operator-upgrade";
  private static final String OP_SA = "operator-sa";
  private static final String DOM_NS = "weblogic-domain";
  private static final String DUID = "domain1";
  private static String opUpgradeTmpDir;
  private Domain domain = null;
  private static Operator operator20;
  private boolean testCompletedSuccessfully = true;

  /**
   * This method gets called only once before any of the test methods are executed. It does the
   * initialization of the integration test properties defined in OperatorIT.properties and setting
   * the resultRoot, pvRoot and projectRoot attributes.
   *
   * @throws Exception
   */
  @BeforeClass
  public static void staticPrepare() throws Exception {
    if (!QUICKTEST) {
      initialize(APP_PROPS_FILE);
      pullImages();
      opUpgradeTmpDir = BaseTest.getResultDir() + "/operatorupgrade";
    }
  }

  @Before
  public void beforeTest() throws Exception {
    logger.log(Level.INFO, "+++++++++++++++Beginning BeforeTest Setup+++++++++++++++++++++");
    Files.createDirectories(Paths.get(opUpgradeTmpDir));
    setEnv("IMAGE_NAME_OPERATOR", "oracle/weblogic-kubernetes-operator");
    setEnv("IMAGE_TAG_OPERATOR", OP_BASE_REL);

    Map<String, Object> operatorMap = TestUtils.loadYaml(OPERATOR1_YAML);
    operatorMap.put("operatorVersion", OP_BASE_REL);
    operatorMap.put("operatorVersionDir", opUpgradeTmpDir);
    operatorMap.put("namespace", OP_NS);
    operatorMap.put("releaseName", OP_DEP_NAME);
    operatorMap.put("serviceAccount", OP_SA);
    List<String> dom_ns = new ArrayList<String>();
    dom_ns.add(DOM_NS);
    operatorMap.put("domainNamespaces", dom_ns);
    operator20 = TestUtils.createOperator(operatorMap, Operator.RESTCertType.LEGACY);

    Map<String, Object> wlstDomainMap = TestUtils.loadYaml(DOMAININIMAGE_WLST_YAML);
    wlstDomainMap.put("domainUID", "operator20domain");
    wlstDomainMap.put("namespace", DOM_NS);
    wlstDomainMap.put("projectRoot", opUpgradeTmpDir + "/weblogic-kubernetes-operator");
    domain = TestUtils.createDomain(wlstDomainMap);
    domain.verifyDomainCreated();
    testBasicUseCases(domain);
    testClusterScaling(operator20, domain);
    logger.log(Level.INFO, "+++++++++++++++Done BeforeTest Setup+++++++++++++++++++++");
  }

  @After
  public void afterTest() throws Exception {
    logger.log(Level.INFO, "+++++++++++++++Beginning AfterTest Setup+++++++++++++++++++++");
    if (domain != null && (JENKINS || testCompletedSuccessfully)) {
      domain.destroy();
    }
    if (operator20 != null && (JENKINS || testCompletedSuccessfully)) {
      operator20.destroy();
      operator20 = null;
    }
    Files.deleteIfExists(Paths.get(opUpgradeTmpDir));
    logger.log(Level.INFO, "+++++++++++++++Done AfterTest Setup+++++++++++++++++++++");
  }

  /**
   * Releases k8s cluster lease, archives result, pv directories
   *
   * @throws Exception
   */
  @AfterClass
  public static void staticUnPrepare() throws Exception {
    if (!QUICKTEST) {
      logger.info("+++++++++++++++++++++++++++++++++---------------------------------+");
      logger.info("BEGIN");
      logger.info("Run once, release cluster lease");
      tearDown(new Object() {}.getClass().getEnclosingClass().getSimpleName());
      logger.info("SUCCESS");
    }
  }

  @Test
  public void testOperatorUpgradeTo2_1() throws Exception {
    String testMethod = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethod);
    // checkout weblogic operator image 2.0
    // pull traefik , wls and operator images
    // create service account, etc.,
    // create traefik loadbalancer
    // create operator
    // create domain

    // pull operator 2.1 image
    // helm upgrade to operator 2.1
    // verify the domain is not restarted but the operator image running is 2.1
    // createOperator();
    // verifyDomainCreated();
    testCompletedSuccessfully = false;
    upgradeOperator("oracle/weblogic-kubernetes-operator:2.1");
    checkOperatorVersion("v2");
    domain.verifyDomainCreated();
    testBasicUseCases(domain);
    testClusterScaling(operator20, domain);
    testCompletedSuccessfully = true;
    logger.info("SUCCESS - " + testMethod);
  }

  @Test
  public void testOperatorUpgradeTo2_2_0() throws Exception {
    String testMethod = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethod);
    testCompletedSuccessfully = false;
    upgradeOperator("oracle/weblogic-kubernetes-operator:2.2.0");
    checkOperatorVersion("v3");
    domain.verifyDomainCreated();
    testBasicUseCases(domain);
    testClusterScaling(operator20, domain);
    testCompletedSuccessfully = true;
    logger.info("SUCCESS - " + testMethod);
  }

  @Test
  public void testOperatorUpgradeTodevelop() throws Exception {
    String testMethod = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethod);
    testCompletedSuccessfully = false;
    upgradeOperator("oracle/weblogic-kubernetes-operator:test_opupgrade");
    checkOperatorVersion("v4");
    domain.verifyDomainCreated();
    testBasicUseCases(domain);
    testClusterScaling(operator20, domain);
    testCompletedSuccessfully = true;
    logger.info("SUCCESS - " + testMethod);
  }

  private static void pullImages() throws Exception {
    TestUtils.exec("docker pull oracle/weblogic-kubernetes-operator:" + OP_BASE_REL);
    TestUtils.exec("docker pull traefik:1.7.6");
    TestUtils.exec(
        "docker pull " + BaseTest.getWeblogicImageName() + ":" + BaseTest.getWeblogicImageTag());
  }

  private void upgradeOperator(String upgradeRelease) throws Exception {
    TestUtils.exec(
        "cd "
            + opUpgradeTmpDir
            + " && helm upgrade --reuse-values --set 'image="
            + upgradeRelease
            + "' --wait "
            + OP_DEP_NAME
            + " weblogic-kubernetes-operator/kubernetes/charts/weblogic-operator");
  }

  private void checkOperatorVersion(String version) throws Exception {
    ExecResult result = ExecCommand.exec("kubectl get domain " + DUID + " -o yaml -n " + DOM_NS);
    if (!result.stdout().contains("apiVersion: weblogic.oracle/" + version)) {
      logger.log(Level.INFO, result.stdout());
      throw new RuntimeException("FAILURE: Didn't get the expected operator version");
    }
  }

  public static void setEnv(String key, String value) {
    try {
      Map<String, String> env = System.getenv();
      Class<?> cl = env.getClass();
      Field field = cl.getDeclaredField("m");
      field.setAccessible(true);
      Map<String, String> writableEnv = (Map<String, String>) field.get(env);
      writableEnv.put(key, value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to set environment variable", e);
    }
  }
}
