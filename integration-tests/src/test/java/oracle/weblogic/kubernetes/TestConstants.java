// Copyright (c) 2020, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.net.InetAddress;
import java.util.Optional;

import static oracle.weblogic.kubernetes.actions.TestActions.listNamespaces;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getBaseImagesPrefixLength;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getDateAndTimeStamp;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getDomainImagePrefix;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getEnvironmentProperty;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getImageRepoFromImageName;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getKindRepoImageForSpec;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getKindRepoValue;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNonEmptySystemProperty;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public interface TestConstants {

  String OLD_DOMAIN_VERSION = "v8";
  Boolean SKIP_CLEANUP =
      Boolean.parseBoolean(getNonEmptySystemProperty("wko.it.skip.cleanup", "false"));
  Boolean COLLECT_LOGS_ON_SUCCESS =
      Boolean.parseBoolean(getNonEmptySystemProperty("wko.it.collect.logs.on.success", "false"));
  int SLEEP_SECONDS_AFTER_FAILURE =
      Integer.parseInt(getNonEmptySystemProperty("wko.it.sleep.seconds.after.failure", "0"));
  String OPDEMO = getNonEmptySystemProperty("wko.it.opdemo");
  //ARM constants
  boolean ARM =
      Boolean.parseBoolean(getNonEmptySystemProperty("wko.it.arm.cluster", "false"));

  // domain constants
  String DOMAIN_VERSION =
      getNonEmptySystemProperty("wko.it.domain.version", "v9");
  String DOMAIN_API_VERSION = "weblogic.oracle/" + DOMAIN_VERSION;
  String ADMIN_SERVER_NAME_BASE = "admin-server";
  String MANAGED_SERVER_NAME_BASE = "managed-server";
  String WLS_DOMAIN_TYPE = "WLS";
  int DEFAULT_MAX_CLUSTER_SIZE = 5;

  // cluster constants
  String CLUSTER_VERSION =
      getNonEmptySystemProperty("wko.it.cluster.version", "v1");
  String CLUSTER_API_VERSION = "weblogic.oracle/" + CLUSTER_VERSION;

  // operator constants
  String OPERATOR_RELEASE_NAME = "weblogic-operator";
  String OPERATOR_CHART_DIR =
      "../kubernetes/charts/weblogic-operator";
  String OPERATOR_RELEASE_IMAGE =
      getNonEmptySystemProperty("wko.it.release.image.name.operator",
          "ghcr.io/oracle/weblogic-kubernetes-operator:4.1.2");
  String IMAGE_NAME_OPERATOR =
      getNonEmptySystemProperty("wko.it.image.name.operator", "oracle/weblogic-kubernetes-operator");
  String OPERATOR_SERVICE_NAME = "internal-weblogic-operator-svc";
  String OPERATOR_GITHUB_CHART_REPO_URL =
      "https://oracle.github.io/weblogic-kubernetes-operator/charts";

  // kind constants
  String KIND_REPO = getKindRepoValue("wko.it.kind.repo");
  String KIND_NODE_NAME = getNonEmptySystemProperty("wko.it.kind.name", "kind");
  boolean KIND_CLUSTER =
      Boolean.parseBoolean((getNonEmptySystemProperty("wko.it.kind.repo") != null) ? "true" : "false");

  // crio pipeline constants
  String CRIO_PIPELINE_IMAGE = System.getProperty("wko.it.crio.pipeline.image");

  // BASE_IMAGES_REPO represents the repository from where all the base WebLogic
  // and InfraStructure images are pulled
  String BASE_IMAGES_REPO = Optional.ofNullable(getImageRepoFromImageName(CRIO_PIPELINE_IMAGE))
      .orElse(System.getProperty("wko.it.base.images.repo"));

  String BASE_IMAGES_TENANCY = System.getProperty("wko.it.base.images.tenancy");

  String BASE_IMAGES_REPO_USERNAME = System.getenv("BASE_IMAGES_REPO_USERNAME");
  String BASE_IMAGES_REPO_PASSWORD = System.getenv("BASE_IMAGES_REPO_PASSWORD");
  String BASE_IMAGES_REPO_EMAIL = System.getenv("BASE_IMAGES_REPO_EMAIL");
  String BASE_IMAGES_REPO_SECRET_NAME = "base-images-repo-secret";

  // TEST_IMAGES_REPO represents the repository (a) which contains few external
  // images such as nginx,elasticsearch,Oracle DB operator (b) all test domain 
  // images to be pushed into it.
  //
  String TEST_IMAGES_REPO = System.getProperty("wko.it.test.images.repo");
  String TEST_IMAGES_TENANCY = System.getProperty("wko.it.test.images.tenancy");
  String TEST_IMAGES_PREFIX = getDomainImagePrefix(TEST_IMAGES_REPO, TEST_IMAGES_TENANCY);

  String TEST_IMAGES_REPO_USERNAME = System.getenv("TEST_IMAGES_REPO_USERNAME");
  String TEST_IMAGES_REPO_PASSWORD = System.getenv("TEST_IMAGES_REPO_PASSWORD");
  String TEST_IMAGES_REPO_EMAIL = System.getenv("TEST_IMAGES_REPO_EMAIL");
  String TEST_IMAGES_REPO_SECRET_NAME = "test-images-repo-secret";

  // Default image names, tags to be used to download base images
  // It depends on the default value of BASE_IMAGES_REPO. 
  // Following defaults are assuming OCIR as default for BASE_IMAGES_REPO.
  String WEBLOGIC_IMAGE_NAME_DEFAULT = "test-images/weblogic";
  String WEBLOGIC_IMAGE_TAG_DEFAULT = "12.2.1.4";
  String FMWINFRA_IMAGE_NAME_DEFAULT = "test-images/fmw-infrastructure";
  String FMWINFRA_IMAGE_TAG_DEFAULT = "12.2.1.4";
  String DB_IMAGE_NAME_DEFAULT = "test-images/database/enterprise";
  String DB_IMAGE_TAG_DEFAULT = "12.2.0.1-slim";

  // repository to push the domain images created during test execution
  // (a) for kind cluster push to kind repo
  // (b) for OKD or OKE push to TEST_IMAGES_REPO 
  // (c) for local runs don't push the domain images to any repo
  String DOMAIN_IMAGES_REPO = Optional.ofNullable(KIND_REPO)
      .orElse(getNonEmptySystemProperty("wko.it.test.images.repo") != null
          ? getNonEmptySystemProperty("wko.it.test.images.repo") : "");

  String DOMAIN_IMAGES_PREFIX = getDomainImagePrefix(DOMAIN_IMAGES_REPO, TEST_IMAGES_TENANCY);

  // Get WEBLOGIC_IMAGE_NAME/WEBLOGIC_IMAGE_TAG from env var, 
  // if its not provided use OCIR default image values
  //
  String BASE_IMAGES_PREFIX = getDomainImagePrefix(BASE_IMAGES_REPO, BASE_IMAGES_TENANCY);
  String WEBLOGIC_IMAGE_NAME = BASE_IMAGES_PREFIX
      + getNonEmptySystemProperty("wko.it.weblogic.image.name", WEBLOGIC_IMAGE_NAME_DEFAULT);
  String WEBLOGIC_IMAGE_TAG = getNonEmptySystemProperty("wko.it.weblogic.image.tag",
       WEBLOGIC_IMAGE_TAG_DEFAULT);

  // Get FMWINFRA_IMAGE_NAME/FMWINFRA_IMAGE_TAG from env var, if it's not
  // provided and if base images repo is OCIR use OCIR default image values
  String FMWINFRA_IMAGE_NAME = BASE_IMAGES_PREFIX
      + getNonEmptySystemProperty("wko.it.fmwinfra.image.name",  FMWINFRA_IMAGE_NAME_DEFAULT);
  String FMWINFRA_IMAGE_TAG = getNonEmptySystemProperty("wko.it.fmwinfra.image.tag",
        FMWINFRA_IMAGE_TAG_DEFAULT);

  // Get DB_IMAGE_NAME/DB_IMAGE_TAG from env var, if it's not provided and
  // if base images repo is OCIR use OCIR default image values
  String DB_IMAGE_NAME = BASE_IMAGES_PREFIX
      + getNonEmptySystemProperty("wko.it.db.image.name", DB_IMAGE_NAME_DEFAULT);
  String DB_IMAGE_TAG = getNonEmptySystemProperty("wko.it.db.image.tag", DB_IMAGE_TAG_DEFAULT);

  // WebLogic Base Image with Japanese Locale
  String LOCALE_IMAGE_NAME = TEST_IMAGES_PREFIX + "test-images/weblogic";
  String LOCALE_IMAGE_TAG = "12.2.1.4-jp";

  // For kind, replace repo name in image name with KIND_REPO, 
  // otherwise use the actual image name
  // For example, if the image name is
  // container-registry.oracle.com/middleware/weblogic:12.2.1.4 
  // it will be pushed/used as
  // localhost:5000/middleware/weblogic:12.2.1.4 in kind and 
  // in non-kind cluster it will be used as is.

  int BASE_IMAGES_REPO_PREFIX_LENGTH = getBaseImagesPrefixLength(BASE_IMAGES_REPO, BASE_IMAGES_TENANCY);
  String WEBLOGIC_IMAGE_TO_USE_IN_SPEC =
      getKindRepoImageForSpec(KIND_REPO, WEBLOGIC_IMAGE_NAME, WEBLOGIC_IMAGE_TAG, BASE_IMAGES_REPO_PREFIX_LENGTH);
  String FMWINFRA_IMAGE_TO_USE_IN_SPEC =
      getKindRepoImageForSpec(KIND_REPO, FMWINFRA_IMAGE_NAME, FMWINFRA_IMAGE_TAG, BASE_IMAGES_REPO_PREFIX_LENGTH);
  String DB_IMAGE_TO_USE_IN_SPEC =
      getKindRepoImageForSpec(KIND_REPO, DB_IMAGE_NAME, DB_IMAGE_TAG, BASE_IMAGES_REPO_PREFIX_LENGTH);

  String DB_19C_IMAGE_TAG = "19.3.0.0";

  // jenkins constants
  String BUILD_ID = System.getProperty("wko.it.jenkins.build.id", "");
  String BRANCH_NAME_FROM_JENKINS = System.getProperty("wko.it.jenkins.branch.name", "");
  String SAFE_BRANCH_IMAGE_NAME =
      BRANCH_NAME_FROM_JENKINS.codePoints().map(cp -> Character.isLetterOrDigit(cp) ? cp : '-')
          .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
  String IMAGE_TAG_OPERATOR = getNonEmptySystemProperty("wko.it.image.tag.operator");
  String IMAGE_TAG_OPERATOR_FOR_JENKINS =
      IMAGE_TAG_OPERATOR != null ? IMAGE_TAG_OPERATOR : SAFE_BRANCH_IMAGE_NAME + BUILD_ID;

  String K8S_NODEPORT_HOST = getNonEmptySystemProperty("wko.it.k8s.nodeport.host",
      assertDoesNotThrow(() -> InetAddress.getLocalHost().getHostAddress()));
  String K8S_NODEPORT_HOSTNAME = getNonEmptySystemProperty("wko.it.k8s.nodeport.host",
        assertDoesNotThrow(() -> InetAddress.getLocalHost().getHostName()));
  String RESULTS_BASE = getNonEmptySystemProperty("wko.it.result.root",
      System.getProperty("java.io.tmpdir") + "/it-testsresults");

  String LOGS_DIR = RESULTS_BASE + "/diagnostics";
  String PV_ROOT =
      getNonEmptySystemProperty("wko.it.pv.root", RESULTS_BASE + "/pvroot");
  String RESULTS_ROOT = RESULTS_BASE + "/workdir";

  // NGINX constants
  String NGINX_REPO_URL = "https://kubernetes.github.io/ingress-nginx";
  String NGINX_RELEASE_NAME = "nginx-release" + BUILD_ID;
  String NGINX_REPO_NAME = "ingress-nginx";
  String NGINX_CHART_NAME = "ingress-nginx";
  String NGINX_CHART_VERSION = "4.0.17";
  String NGINX_INGRESS_IMAGE_DIGEST =
      "sha256:314435f9465a7b2973e3aa4f2edad7465cc7bcdc8304be5d146d70e4da136e51";
  String TEST_NGINX_IMAGE_NAME = TEST_IMAGES_TENANCY + "/test-images/ingress-nginx/controller";
  String NGINX_INGRESS_IMAGE_TAG = "v1.2.0";

  // Traefik constants
  String TRAEFIK_REPO_URL = "https://helm.traefik.io/traefik";
  String TRAEFIK_RELEASE_NAME = "traefik-release" + BUILD_ID;
  String TRAEFIK_REPO_NAME = "traefik";
  String TRAEFIK_CHART_NAME = "traefik";
  String TRAEFIK_INGRESS_IMAGE_NAME = TEST_IMAGES_TENANCY + "/test-images/traefik";
  String TRAEFIK_INGRESS_IMAGE_REGISTRY = TEST_IMAGES_REPO;

  String TRAEFIK_INGRESS_IMAGE_TAG = "v2.10.5";

  // ELK Stack and WebLogic logging exporter constants
  String ELASTICSEARCH_NAME = "elasticsearch";
  String ELASTICSEARCH_IMAGE_NAME = ARM ? TEST_IMAGES_PREFIX + "test-images/elasticsearch"
      : TEST_IMAGES_PREFIX + "test-images/docker/elasticsearch";
  String ELK_STACK_VERSION = ARM ? "8.10.3-arm64" : "7.8.1";
  String FLUENTD_IMAGE_VERSION =
      getNonEmptySystemProperty("wko.it.fluentd.image.version", "v1.14.5");
  String ELASTICSEARCH_IMAGE = ELASTICSEARCH_IMAGE_NAME + ":" + ELK_STACK_VERSION;
  String ELASTICSEARCH_HOST = "elasticsearch.default.svc.cluster.local";
  int DEFAULT_LISTEN_PORT = 7100;
  int ELASTICSEARCH_HTTP_PORT = 9200;
  int ELASTICSEARCH_HTTPS_PORT = 9300;
  String LOGSTASH_INDEX_KEY = "logstash";
  String FLUENTD_INDEX_KEY = "fluentd";
  String INTROSPECTOR_INDEX_KEY = "introspectord";
  String KIBANA_INDEX_KEY = "kibana";
  String KIBANA_NAME = "kibana";
  String KIBANA_IMAGE_NAME = ARM ? TEST_IMAGES_PREFIX + "test-images/kibana"
      : TEST_IMAGES_PREFIX + "test-images/docker/kibana";
  String KIBANA_IMAGE = KIBANA_IMAGE_NAME + ":" + ELK_STACK_VERSION;
  String KIBANA_TYPE = "NodePort";
  int KIBANA_PORT = 5601;
  String LOGSTASH_IMAGE_NAME = ARM ? TEST_IMAGES_PREFIX + "test-images/logstash" :
      TEST_IMAGES_PREFIX + "test-images/docker/logstash";
  String LOGSTASH_IMAGE = LOGSTASH_IMAGE_NAME + ":" + ELK_STACK_VERSION;
  String FLUENTD_IMAGE = TEST_IMAGES_PREFIX
            + "test-images/docker/fluentd-kubernetes-daemonset:"
            + FLUENTD_IMAGE_VERSION;
  String JAVA_LOGGING_LEVEL_VALUE = "INFO";

  // MII image constants
  String MII_BASIC_WDT_MODEL_FILE = "model-singleclusterdomain-sampleapp-wls.yaml";
  String MII_BASIC_IMAGE_NAME = DOMAIN_IMAGES_PREFIX + "mii-basic-image";
  String MII_AUXILIARY_IMAGE_NAME = DOMAIN_IMAGES_PREFIX + "mii-ai-image";
  boolean SKIP_BUILD_IMAGES_IF_EXISTS =
      Boolean.parseBoolean(getNonEmptySystemProperty("wko.it.skip.build.images.if.exists", "false"));

  String BUSYBOX_IMAGE = TEST_IMAGES_PREFIX + "test-images/docker/busybox";
  String BUSYBOX_TAG = "1.36";

  // Skip the mii/wdt basic image build locally if needed
  String MII_BASIC_IMAGE_TAG = SKIP_BUILD_IMAGES_IF_EXISTS ? "local" : getDateAndTimeStamp();
  String MII_BASIC_IMAGE_DOMAINTYPE = "mii";
  String MII_BASIC_APP_NAME = "sample-app";
  String MII_BASIC_APP_DEPLOYMENT_NAME = "myear";
  String MII_TWO_APP_WDT_MODEL_FILE = "model-singlecluster-two-sampleapp-wls.yaml";
  String MII_UPDATED_RESTART_REQUIRED_LABEL = "weblogic.configChangesPendingRestart";

  // application constants
  String MII_APP_RESPONSE_V1 = "Hello World, you have reached server managed-server";
  String MII_APP_RESPONSE_V2 = "Hello World AGAIN, you have reached server managed-server";
  String MII_APP_RESPONSE_V3 = "How are you doing! You have reached server managed-server";

  // WDT domain-in-image constants
  String WDT_BASIC_MODEL_FILE = "wdt-singlecluster-sampleapp-usingprop-wls.yaml";
  String WDT_BASIC_MODEL_PROPERTIES_FILE = "wdt-singleclusterdomain-sampleapp-wls.properties";
  String WDT_BASIC_IMAGE_NAME = DOMAIN_IMAGES_PREFIX + "wdt-basic-image";
  // Skip the mii/wdt basic image build locally if needed
  String WDT_BASIC_IMAGE_TAG = SKIP_BUILD_IMAGES_IF_EXISTS ? "local" : getDateAndTimeStamp();
  String WDT_BASIC_IMAGE_DOMAINHOME = "/u01/oracle/user_projects/domains/domain1";
  String WDT_IMAGE_DOMAINHOME_BASE_DIR = "/u01/oracle/user_projects/domains";
  String WDT_BASIC_IMAGE_DOMAINTYPE = "wdt";
  String WDT_BASIC_APP_NAME = "sample-app";

  // Here we need an old version of WDT to build an Auxiliary Image with
  // WDT binary only. Later it will be overwritten by latest WDT version
  // See ItMiiAuxiliaryImage.testUpdateWDTVersionUsingMultipleAuxiliaryImages
  String WDT_TEST_VERSION =
      getNonEmptySystemProperty("wko.it.wdt.test.version", "1.9.20");

  //monitoring constants
  String MONITORING_EXPORTER_WEBAPP_VERSION =
      getNonEmptySystemProperty("wko.it.monitoring.exporter.webapp.version", "2.1.3");
  String MONITORING_EXPORTER_BRANCH =
      getNonEmptySystemProperty("wko.it.monitoring.exporter.branch", "main");
  String PROMETHEUS_CHART_VERSION =
      getNonEmptySystemProperty("wko.it.prometheus.chart.version", "17.0.0");
  String GRAFANA_CHART_VERSION =
      getNonEmptySystemProperty("wko.it.grafana.chart.version", "6.44.11");
  String PROMETHEUS_REPO_NAME = "prometheus-community";
  String PROMETHEUS_REPO_URL = "https://prometheus-community.github.io/helm-charts";

  String PROMETHEUS_IMAGE_NAME = TEST_IMAGES_PREFIX + "test-images/prometheus/prometheus";
  String PROMETHEUS_IMAGE_TAG = "v2.39.1";

  String PROMETHEUS_ALERT_MANAGER_IMAGE_NAME = TEST_IMAGES_PREFIX
      + "test-images/prometheus/alertmanager";
  String PROMETHEUS_ALERT_MANAGER_IMAGE_TAG = "v0.24.0";

  String PROMETHEUS_CONFIG_MAP_RELOAD_IMAGE_NAME = TEST_IMAGES_PREFIX
      + "test-images/jimmidyson/configmap-reload";
  String PROMETHEUS_CONFIG_MAP_RELOAD_IMAGE_TAG = "v0.5.0";

  String PROMETHEUS_PUSHGATEWAY_IMAGE_NAME = TEST_IMAGES_PREFIX
      + "test-images/prometheus/pushgateway";
  String PROMETHEUS_PUSHGATEWAY_IMAGE_TAG = "v1.4.3";

  String PROMETHEUS_NODE_EXPORTER_IMAGE_NAME = TEST_IMAGES_PREFIX
      + "test-images/prometheus/node-exporter";
  String PROMETHEUS_NODE_EXPORTER_IMAGE_TAG = "v1.3.1";

  String GRAFANA_REPO_NAME = "grafana";
  String GRAFANA_REPO_URL = "https://grafana.github.io/helm-charts";
  String GRAFANA_IMAGE_NAME = TEST_IMAGES_PREFIX + "test-images/grafana/grafana";
  String GRAFANA_IMAGE_TAG = "9.3.0";

  // credentials
  String ADMIN_USERNAME_DEFAULT = "weblogic";
  String ADMIN_PASSWORD_DEFAULT = "welcome1";
  String ADMIN_USERNAME_PATCH = "weblogicnew";
  String ADMIN_PASSWORD_PATCH = "welcome1new";

  String ENCRYPION_USERNAME_DEFAULT = "weblogicenc";
  String ENCRYPION_PASSWORD_DEFAULT = "weblogicenc";

  // REST API
  String PROJECT_ROOT = System.getProperty("user.dir");
  String GEN_EXTERNAL_REST_IDENTITY_FILE =
      PROJECT_ROOT + "/../kubernetes/samples/scripts/rest/generate-external-rest-identity.sh";
  String DEFAULT_EXTERNAL_REST_IDENTITY_SECRET_NAME = "weblogic-operator-external-rest-identity";

  String ISTIO_VERSION =
      getNonEmptySystemProperty("wko.it.istio.version", "1.13.2");

  //MySQL database constants
  String MYSQL_IMAGE = BASE_IMAGES_PREFIX + "test-images/database/mysql";
  String MYSQL_VERSION = "8.0.29";

  //OKE constants
  boolean OKE_CLUSTER =
      Boolean.parseBoolean(getNonEmptySystemProperty("wko.it.oke.cluster", "false"));
  boolean OKE_CLUSTER_PRIVATEIP =
      Boolean.parseBoolean(getNonEmptySystemProperty("wko.it.oke.cluster.privateip", "false"));
  String NFS_SERVER = System.getProperty("wko.it.nfs.server", "");
  String NODE_IP = System.getProperty("wko.it.node.ip", "");
  String [] FSS_DIR = System.getProperty("wko.it.fss.dir","").split(",");
  String IMAGE_PULL_POLICY = "IfNotPresent";

  //OKD constants
  boolean OKD =
      Boolean.parseBoolean(getNonEmptySystemProperty("wko.it.okd.cluster", "false"));

  // OCNE constants
  boolean OCNE =
      Boolean.parseBoolean(getNonEmptySystemProperty("wko.it.ocne.cluster", "false"));

  // default name suffixes
  String DEFAULT_EXTERNAL_SERVICE_NAME_SUFFIX = "-ext";
  String DEFAULT_INTROSPECTOR_JOB_NAME_SUFFIX = "-introspector";

  // MII domain dynamic update
  String MII_DYNAMIC_UPDATE_EXPECTED_ERROR_MSG =
      "The Domain resource specified 'spec.configuration.model.onlineUpdate.enabled=true', "
          + "but there are unsupported model changes for online update";
  String SSL_PROPERTIES =
      "-Dweblogic.security.SSL.ignoreHostnameVerification=true -Dweblogic.security.TrustKeyStore=DemoTrust";

  boolean WEBLOGIC_SLIM = WEBLOGIC_IMAGE_TAG.contains("slim");
  boolean WEBLOGIC_12213 = WEBLOGIC_IMAGE_TAG.contains("12.2.1.3")
          && !WEBLOGIC_IMAGE_TAG.toLowerCase().contains("cpu");

  String WEBLOGIC_VERSION = WEBLOGIC_IMAGE_TAG.substring(0,8) + ".0";
  String HTTP_PROXY =
      Optional.ofNullable(System.getenv("HTTP_PROXY")).orElse(System.getenv("http_proxy"));
  String HTTPS_PROXY =
      Optional.ofNullable(System.getenv("HTTPS_PROXY")).orElse(System.getenv("https_proxy"));
  String NO_PROXY =
      Optional.ofNullable(System.getenv("NO_PROXY")).orElse(System.getenv("no_proxy"));

  // domain status condition type
  String DOMAIN_STATUS_CONDITION_COMPLETED_TYPE = "Completed";
  String DOMAIN_STATUS_CONDITION_PROGRESSING_TYPE = "Progressing";
  String DOMAIN_STATUS_CONDITION_AVAILABLE_TYPE = "Available";
  String DOMAIN_STATUS_CONDITION_FAILED_TYPE = "Failed";
  String DOMAIN_STATUS_CONDITION_ROLLING_TYPE = "Rolling";

  // Oracle RCU setup constants
  String ORACLE_RCU_SECRET_NAME = "oracle-rcu-secret";
  String ORACLE_RCU_SECRET_VOLUME = "oracle-rcu-volume";
  String ORACLE_RCU_SECRET_MOUNT_PATH = "/rcu-secret";

  // Oracle database "no operator" constant(s)
  String ORACLE_DB_SECRET_NAME = "oracle-db-secret";

  // Oracle database operator constants
  String DB_OPERATOR_IMAGE = BASE_IMAGES_PREFIX + "test-images/database/operator:0.2.1";
  String CERT_MANAGER
      = "https://github.com/jetstack/cert-manager/releases/latest/download/cert-manager.yaml";
  String ORACLELINUX_TEST_VERSION =
      getNonEmptySystemProperty("wko.it.oraclelinux.test.version", "7");

  // retry improvement
  // Defaulting to 120 seconds
  Long FAILURE_RETRY_INTERVAL_SECONDS =
      Long.valueOf(getNonEmptySystemProperty("failure.retry.interval.seconds", "20"));
  // Defaulting to 1440 minutes (24 hours)
  Long FAILURE_RETRY_LIMIT_MINUTES =
      Long.valueOf(getNonEmptySystemProperty("failure.retry.limit.minutes", "10"));
  String YAML_MAX_FILE_SIZE_PROPERTY = "-Dwdt.config.yaml.max.file.size=25000000";

  // kubernetes CLI, some may set this to 'oc'
  String KUBERNETES_CLI_DEFAULT = "kubectl";
  String KUBERNETES_CLI =
      getEnvironmentProperty("KUBERNETES_CLI", KUBERNETES_CLI_DEFAULT);

  // image build CLI, some may set this to 'podman'
  //note: 'WLSIMG_BUILDER' is the same name as the env-var/prop used by WIT
  String WLSIMG_BUILDER_DEFAULT = "docker";
  String WLSIMG_BUILDER =
      getEnvironmentProperty("WLSIMG_BUILDER", WLSIMG_BUILDER_DEFAULT);

  // metrics server constants
  String METRICS_SERVER_YAML =
      "https://github.com/kubernetes-sigs/metrics-server/releases/download/metrics-server-helm-chart-3.8.2/components.yaml";
  
  // verrazzano related constants
  String VZ_INGRESS_NS = "ingress-nginx";
  String VZ_SYSTEM_NS = "verrazzano-system";
  String VZ_ISTIO_NS = "istio-system";
  boolean VZ_ENV = assertDoesNotThrow(() -> listNamespaces().stream()
        .anyMatch(ns -> ns.equals(VZ_SYSTEM_NS)));
  String LARGE_DOMAIN_TESTING_PROPS_FILE =
      "largedomaintesting.props";
}
