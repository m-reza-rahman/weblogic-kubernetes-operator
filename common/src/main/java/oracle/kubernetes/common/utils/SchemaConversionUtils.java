// Copyright (c) 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.common.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import oracle.kubernetes.common.AuxiliaryImageConstants;
import oracle.kubernetes.common.CommonConstants;
import oracle.kubernetes.common.helpers.AuxiliaryImageEnvVars;
import oracle.kubernetes.common.logging.BaseLoggingFacade;
import oracle.kubernetes.common.logging.CommonLoggingFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import static oracle.kubernetes.common.CommonConstants.API_VERSION_V8;
import static oracle.kubernetes.common.CommonConstants.API_VERSION_V9;

@SuppressWarnings({"unchecked", "rawtypes"})
public class SchemaConversionUtils {

  /**
   * The list of failure reason strings. Hard-coded here to match the values in DomainFailureReason.
   * Validated in tests in the operator.
   */
  public static final List<String> SUPPORTED_FAILURE_REASONS = List.of(
        "Aborted", "Internal", "TopologyMismatch", "ReplicasTooHigh",
        "ServerPod", "Kubernetes", "Introspection", "DomainInvalid");

  /**
   * The list of condition types no longer supported. Should match the values in DomainConditionType with
   * a true value for isObsolete(). Validated in tests in the operator.
   */
  public static final List<String> OBSOLETE_CONDITION_TYPES = List.of("Progressing");

  private static final BaseLoggingFacade LOGGER = CommonLoggingFactory.getLogger("Webhook", "Operator");
  private static final String VOLUME_MOUNTS = "volumeMounts";
  private static final String VOLUME = "volume";
  private static final String MOUNT_PATH = "mountPath";
  private static final String IMAGE = "image";

  private final AtomicInteger containerIndex = new AtomicInteger(0);
  private final String targetAPIVersion;

  public SchemaConversionUtils(String targetAPIVersion) {
    this.targetAPIVersion = targetAPIVersion;
  }

  public SchemaConversionUtils() {
    this(API_VERSION_V9);
  }

  public static SchemaConversionUtils create() {
    return create(API_VERSION_V9);
  }

  public static SchemaConversionUtils create(String targetAPIVersion) {
    return new SchemaConversionUtils(targetAPIVersion);
  }

  /**
   * Convert the domain schema to desired API version.
   * @param domain Domain to be converted.
   * @return Domain The converted domain.
   */
  @SuppressWarnings("java:S112")
  public Map<String, Object> convertDomainSchema(Map<String, Object> domain) {
    Map<String, Object> spec = getSpec(domain);
    if (spec == null) {
      return domain;
    }
    LOGGER.fine("Converting domain " + domain + " to " + targetAPIVersion + " apiVersion.");

    String apiVersion = (String) domain.get("apiVersion");
    adjustAdminPortForwardingDefault(spec, apiVersion);
    convertLegacyAuxiliaryImages(spec);
    removeObsoleteConditionsFromDomainStatus(domain);
    removeUnsupportedDomainStatusConditionReasons(domain);
    convertDomainHomeInImageToDomainHomeSourceType(domain);
    moveConfigOverrides(domain);
    moveConfigOverrideSecrets(domain);
    constantsToCamelCase(spec);
    adjustReplicasDefault(spec, apiVersion);

    Map<String, Object> toBePreserved = new HashMap<>();
    convertServerStartState(spec, toBePreserved);

    try {
      preserve(domain, toBePreserved, apiVersion);
      restore(domain);
    } catch (IOException io) {
      throw new RuntimeException(io); // need to use unchecked exception because of stream processing
    }

    domain.put("apiVersion", targetAPIVersion);
    LOGGER.fine("Converted domain with " + targetAPIVersion + " apiVersion is " + domain);
    return domain;
  }

  /**
   * Convert the domain schema to desired API version.
   * @param domainYaml Domain yaml to be converted.
   * @return Domain String containing the converted domain yaml.
   */
  public String convertDomainSchema(String domainYaml) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    Yaml yaml = new Yaml(options);
    Object domain = yaml.load(domainYaml);
    return yaml.dump(convertDomainSchema((Map<String, Object>) domain));
  }

  private void adjustAdminPortForwardingDefault(Map<String, Object> spec, String apiVersion) {
    Map<String, Object> adminServerSpec = (Map<String, Object>) spec.get("adminServer");
    Boolean adminChannelPortForwardingEnabled = (Boolean) Optional.ofNullable(adminServerSpec)
            .map(as -> as.get("adminChannelPortForwardingEnabled")).orElse(null);
    if ((adminChannelPortForwardingEnabled == null) && (CommonConstants.API_VERSION_V8.equals(apiVersion))) {
      Optional.ofNullable(adminServerSpec).ifPresent(as -> as.put("adminChannelPortForwardingEnabled", false));
    }
  }

  private void convertLegacyAuxiliaryImages(Map<String, Object> spec) {
    List<Object> auxiliaryImageVolumes = Optional.ofNullable(getAuxiliaryImageVolumes(spec)).orElse(new ArrayList<>());
    convertAuxiliaryImages(spec, auxiliaryImageVolumes);
    Optional.ofNullable(getAdminServer(spec)).ifPresent(as -> convertAuxiliaryImages(as, auxiliaryImageVolumes));
    Optional.ofNullable(getClusters(spec)).ifPresent(cl -> cl.forEach(cluster ->
            convertAuxiliaryImages((Map<String, Object>) cluster, auxiliaryImageVolumes)));
    Optional.ofNullable(getManagedServers(spec)).ifPresent(ms -> ms.forEach(managedServer ->
            convertAuxiliaryImages((Map<String, Object>) managedServer, auxiliaryImageVolumes)));
    spec.remove("auxiliaryImageVolumes");
  }

  private void removeObsoleteConditionsFromDomainStatus(Map<String, Object> domain) {
    getStatusConditions(domain).removeIf(this::isObsoleteCondition);
  }

  private boolean isObsoleteCondition(Map<String, String> condition) {
    return Optional.ofNullable(condition.get("type")).map(this::isObsoleteConditionType).orElse(true);
  }

  private boolean isObsoleteConditionType(String type) {
    return OBSOLETE_CONDITION_TYPES.contains(type);
  }

  @Nonnull
  private List<Map<String,String>> getStatusConditions(Map<String, Object> domain) {
    return (List<Map<String,String>>) Optional.ofNullable((Map<String, Object>) domain.get("status"))
          .map(status -> status.get("conditions"))
          .orElse(Collections.emptyList());
  }

  private void removeUnsupportedDomainStatusConditionReasons(Map<String, Object> domain) {
    getStatusConditions(domain).forEach(this::removeReasonIfUnsupported);
  }

  private void removeReasonIfUnsupported(Map<String, String> condition) {
    removeIf(condition, "reason", this::isUnsupportedReason);
  }

  @SuppressWarnings("SameParameterValue")
  private void removeIf(Map<String, String> map, String key, Predicate<String> predicate) {
    if (Optional.ofNullable(map.get(key)).map(predicate::test).orElse(false)) {
      map.remove(key);
    }
  }

  private boolean isUnsupportedReason(@Nonnull String reason) {
    return !SUPPORTED_FAILURE_REASONS.contains(reason);
  }

  private void convertDomainHomeInImageToDomainHomeSourceType(Map<String, Object> domain) {
    Map<String, Object> domainSpec = (Map<String, Object>) domain.get("spec");
    if (domainSpec != null) {
      Object existing = domainSpec.remove("domainHomeInImage");
      if (existing != null && !domainSpec.containsKey("domainHomeSourceType")) {
        domainSpec.put("domainHomeSourceType",
                Boolean.parseBoolean((String) existing) ? "Image" : "PersistentVolume");
      }
    }
  }

  private Map<String, Object> getOrCreateConfiguration(Map<String, Object> domainSpec) {
    return (Map<String, Object>) domainSpec.computeIfAbsent("configuration", k -> new LinkedHashMap<>());
  }

  private void moveConfigOverrides(Map<String, Object> domain) {
    Map<String, Object> domainSpec = (Map<String, Object>) domain.get("spec");
    if (domainSpec != null) {
      Object existing = domainSpec.remove("configOverrides");
      if (existing != null) {
        Map<String, Object> configuration = getOrCreateConfiguration(domainSpec);
        configuration.putIfAbsent("overridesConfigMap", existing);
      }
    }
  }

  private void moveConfigOverrideSecrets(Map<String, Object> domain) {
    Map<String, Object> domainSpec = (Map<String, Object>) domain.get("spec");
    if (domainSpec != null) {
      Object existing = domainSpec.remove("configOverrideSecrets");
      if (existing != null) {
        Map<String, Object> configuration = getOrCreateConfiguration(domainSpec);
        configuration.putIfAbsent("secrets", existing);
      }
    }
  }

  private void constantsToCamelCase(Map<String, Object> spec) {
    convertServerStartPolicy(spec);
    Optional.ofNullable(getAdminServer(spec)).ifPresent(this::convertServerStartPolicy);
    Optional.ofNullable(getClusters(spec)).ifPresent(cl -> cl.forEach(cluster ->
            convertServerStartPolicy((Map<String, Object>) cluster)));
    Optional.ofNullable(getManagedServers(spec)).ifPresent(ms -> ms.forEach(managedServer ->
            convertServerStartPolicy((Map<String, Object>) managedServer)));

    Optional.ofNullable(getConfiguration(spec)).ifPresent(this::convertOverrideDistributionStrategy);

    convertLogHomeLayout(spec);
  }

  private void convertServerStartPolicy(Map<String, Object> spec) {
    spec.computeIfPresent("serverStartPolicy", this::serverStartPolicyCamelCase);
  }

  public static <K, V> Map<V, K> invertMapUsingMapper(Map<K, V> sourceMap) {
    return sourceMap.entrySet().stream().collect(
            Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (oldValue, newValue) -> oldValue));
  }

  private static Object convertWithMap(Map<String, String> map, Object value) {
    if (value instanceof String) {
      return map.get(value);
    }
    return value;
  }

  private Map<String, String> select(Map<String, String> apiVersion9, Map<String, String> apiVersion8) {
    switch (targetAPIVersion) {
      case API_VERSION_V8:
        return apiVersion8;
      case API_VERSION_V9:
      default:
        return apiVersion9;
    }
  }

  private static final Map<String, String> serverStartPolicyMap = Map.of(
      "ALWAYS", "Always", "NEVER", "Never", "IF_NEEDED", "IfNeeded", "ADMIN_ONLY", "AdminOnly");

  private static final Map<String, String> invertServerStartPolicyMap = invertMapUsingMapper(serverStartPolicyMap);

  private Object serverStartPolicyCamelCase(String key, Object value) {
    return convertWithMap(select(serverStartPolicyMap, invertServerStartPolicyMap), value);
  }

  private void convertOverrideDistributionStrategy(Map<String, Object> configuration) {
    configuration.computeIfPresent("overrideDistributionStrategy", this::overrideDistributionStrategyCamelCase);
  }

  private static final Map<String, String> overrideDistributionStrategyMap = Map.of(
          "DYNAMIC", "Dynamic", "ON_RESTART", "OnRestart");

  private static final Map<String, String> invertOverrideDistributionStrategyMap =
          invertMapUsingMapper(overrideDistributionStrategyMap);

  private Object overrideDistributionStrategyCamelCase(String key, Object value) {
    return convertWithMap(select(overrideDistributionStrategyMap, invertOverrideDistributionStrategyMap), value);
  }

  private void convertLogHomeLayout(Map<String, Object> configuration) {
    configuration.computeIfPresent("logHomeLayout", this::logHomeLayoutCamelCase);
  }

  private static final Map<String, String> logHomeLayoutMap = Map.of(
          "FLAT", "Flat", "BY_SERVERS", "ByServers");

  private static final Map<String, String> invertLogHomeLayoutMap = invertMapUsingMapper(logHomeLayoutMap);

  private Object logHomeLayoutCamelCase(String key, Object value) {
    return convertWithMap(select(logHomeLayoutMap, invertLogHomeLayoutMap), value);
  }

  private List<Object> getAuxiliaryImageVolumes(Map<String, Object> spec) {
    return (List<Object>) spec.get("auxiliaryImageVolumes");
  }

  private Map<String, Object> getConfiguration(Map<String, Object> spec) {
    return (Map<String, Object>) spec.get("configuration");
  }

  private Map<String, Object> getAdminServer(Map<String, Object> spec) {
    return (Map<String, Object>) spec.get("adminServer");
  }

  private List<Object> getClusters(Map<String, Object> spec) {
    return (List<Object>) spec.get("clusters");
  }

  private List<Object> getManagedServers(Map<String, Object> spec) {
    return (List<Object>) spec.get("managedServers");
  }

  private void convertAuxiliaryImages(Map<String, Object> spec, List<Object> auxiliaryImageVolumes) {
    Map<String, Object> serverPod = getServerPod(spec);
    Optional.ofNullable(serverPod).map(this::getAuxiliaryImages).ifPresent(auxiliaryImages ->
            addInitContainersVolumeAndMountsToServerPod(serverPod, auxiliaryImages, auxiliaryImageVolumes));
    Optional.ofNullable(serverPod).ifPresent(cs -> cs.remove("auxiliaryImages"));
  }

  private List<Object> getAuxiliaryImages(Map<String, Object> serverPod) {
    return (List<Object>) Optional.ofNullable(serverPod).map(sp -> (sp.get("auxiliaryImages"))).orElse(null);
  }

  private Map<String, Object> getServerPod(Map<String, Object> spec) {
    return (Map<String, Object>) Optional.ofNullable(spec).map(s -> s.get("serverPod")).orElse(null);
  }

  Map<String, Object> getSpec(Map<String, Object> domain) {
    return (Map<String, Object>) domain.get("spec");
  }

  Map<String, Object> getMetadata(Map<String, Object> domain) {
    return (Map<String, Object>) domain.get("metadata");
  }

  private void addInitContainersVolumeAndMountsToServerPod(Map<String, Object> serverPod, List<Object> auxiliaryImages,
                                                                         List<Object> auxiliaryImageVolumes) {
    addEmptyDirVolume(serverPod, auxiliaryImageVolumes);
    List<Object> initContainers = new ArrayList<>();
    for (Object auxiliaryImage : auxiliaryImages) {
      initContainers.add(
          createInitContainerForAuxiliaryImage((Map<String, Object>) auxiliaryImage, containerIndex.get(),
              auxiliaryImageVolumes));
      containerIndex.addAndGet(1);
    }
    serverPod.put("initContainers", initContainers);
    auxiliaryImages.forEach(ai -> addVolumeMount(serverPod, (Map<String, Object>)ai, auxiliaryImageVolumes));
    addAuxiliaryImageEnv(auxiliaryImages, serverPod, auxiliaryImageVolumes);
  }

  private void addAuxiliaryImageEnv(List<Object> auxiliaryImages, Map<String, Object> serverPod,
                                    List<Object> auxiliaryImageVolumes) {
    List<Object> vars = Optional.ofNullable((List<Object>)serverPod.get("env")).orElse(new ArrayList<>());
    Optional.ofNullable(auxiliaryImages).flatMap(ais ->
            Optional.ofNullable(getAuxiliaryImagePaths(ais, auxiliaryImageVolumes)))
            .ifPresent(c -> addEnvVar(vars, AuxiliaryImageEnvVars.AUXILIARY_IMAGE_PATHS, c));
    serverPod.put("env", vars);
  }

  private String getAuxiliaryImagePaths(List<Object> auxiliaryImages,
                                 List<Object> auxiliaryImageVolumes) {
    return Optional.ofNullable(auxiliaryImages).map(
          aiList -> createauxiliaryImagePathsEnv(aiList, auxiliaryImageVolumes)).orElse(null);
  }

  private String createauxiliaryImagePathsEnv(List<Object> auxiliaryImages, List<Object> auxiliaryImageVolumes) {
    StringJoiner auxiliaryImagePaths = new StringJoiner(",","","");
    auxiliaryImages.forEach(auxiliaryImage -> auxiliaryImagePaths.add(
          getMountPath((Map<String,Object>)auxiliaryImage, auxiliaryImageVolumes)));
    return Arrays.stream(auxiliaryImagePaths.toString().split(Pattern.quote(","))).distinct()
          .filter(st -> !st.isEmpty()).collect(Collectors.joining(","));
  }

  private void addEmptyDirVolume(Map<String,Object> serverPod, List<Object> auxiliaryImageVolumes) {
    Optional.ofNullable(auxiliaryImageVolumes).ifPresent(volumes -> volumes.forEach(auxiliaryImageVolume ->
          addVolumeIfMissing(serverPod, (Map<String, Object>)auxiliaryImageVolume)));
  }

  private void addVolumeIfMissing(Map<String, Object> serverPod, Map<String, Object> auxiliaryImageVolume) {
    List<Object> existingVolumes = Optional.ofNullable((List<Object>)serverPod.get("volumes"))
            .orElse(new ArrayList<>());
    if (Optional.of(existingVolumes).map(volumes -> (volumes).stream().noneMatch(
          volume -> podHasMatchingVolumeName((Map<String, Object>)volume, auxiliaryImageVolume))).orElse(true)) {
      existingVolumes.addAll(Collections.singletonList(createEmptyDirVolume(auxiliaryImageVolume)));
    }
    serverPod.put("volumes", existingVolumes);
  }

  private boolean podHasMatchingVolumeName(Map<String, Object> volume, Map<String, Object> auxiliaryImageVolume) {
    return (volume.get("name")).equals(auxiliaryImageVolume.get("name"));
  }

  private Map<String,Object> createEmptyDirVolume(Map<String, Object> auxiliaryImageVolume) {
    Map<String, Object> emptyDirVolumeSource = new LinkedHashMap<>();
    Optional.ofNullable(auxiliaryImageVolume.get("medium")).ifPresent(m -> emptyDirVolumeSource.put("medium", m));
    Optional.ofNullable(auxiliaryImageVolume.get("sizeLimit"))
          .ifPresent(sl -> emptyDirVolumeSource.put("sizeLimit", sl));
    Map<String, Object> emptyDirVolume = new LinkedHashMap<>();
    emptyDirVolume.put("name", getDNS1123auxiliaryImageVolumeName(auxiliaryImageVolume.get("name")));
    emptyDirVolume.put("emptyDir", emptyDirVolumeSource);
    return emptyDirVolume;
  }

  private void addVolumeMountIfMissing(Map<String, Object> serverPod, Map<String, Object> auxiliaryImage,
                                       String mountPath) {
    if (Optional.ofNullable(serverPod.get(VOLUME_MOUNTS)).map(volumeMounts -> ((List)volumeMounts).stream().noneMatch(
          volumeMount -> hasMatchingVolumeMountName(volumeMount, auxiliaryImage))).orElse(true)) {
      serverPod.put(VOLUME_MOUNTS, Collections.singletonList(getVolumeMount(auxiliaryImage, mountPath)));
    }
  }

  private Object getVolumeMount(Map auxiliaryImage, String mountPath) {
    Map<String, String> volumeMount = new LinkedHashMap<>();
    volumeMount.put("name", getDNS1123auxiliaryImageVolumeName(auxiliaryImage.get(VOLUME)));
    volumeMount.put(MOUNT_PATH, mountPath);
    return volumeMount;
  }

  private Map<String, String> getScriptsVolumeMount() {
    Map<String, String> volumeMount = new LinkedHashMap<>();
    volumeMount.put("name", CommonConstants.SCRIPTS_VOLUME);
    volumeMount.put(MOUNT_PATH, CommonConstants.SCRIPTS_MOUNTS_PATH);
    return volumeMount;
  }

  private boolean hasMatchingVolumeMountName(Object volumeMount, Map<String, Object> auxiliaryImage) {
    return getDNS1123auxiliaryImageVolumeName(auxiliaryImage.get(VOLUME)).equals(((Map)volumeMount).get("name"));
  }

  public static String getDNS1123auxiliaryImageVolumeName(Object name) {
    return CommonUtils.toDns1123LegalName(CommonConstants.COMPATIBILITY_MODE
        + AuxiliaryImageConstants.AUXILIARY_IMAGE_VOLUME_NAME_PREFIX + name);
  }

  private void addVolumeMount(Map<String, Object> serverPod, Map<String, Object> auxiliaryImage,
                           List<Object> auxiliaryImageVolumes) {
    Optional.ofNullable(getMountPath(auxiliaryImage,
          auxiliaryImageVolumes)).ifPresent(mountPath ->
          addVolumeMountIfMissing(serverPod, auxiliaryImage, mountPath));
  }


  private String getMountPath(Map<String, Object> auxiliaryImage, List<Object> auxiliaryImageVolumes) {
    return auxiliaryImageVolumes.stream().filter(
          auxiliaryImageVolume -> hasMatchingVolumeName((Map)auxiliaryImageVolume, auxiliaryImage)).findFirst()
          .map(aiv -> (String)((Map<String, Object>) aiv).get(MOUNT_PATH)).orElse(null);
  }

  private boolean hasMatchingVolumeName(Map<String, Object> auxiliaryImageVolume, Map<String, Object> auxiliaryImage) {
    return Optional.ofNullable(auxiliaryImage.get(VOLUME))
            .map(v -> v.equals(auxiliaryImageVolume.get("name"))).orElse(false);
  }

  private Map<String, Object> createInitContainerForAuxiliaryImage(Map<String, Object> auxiliaryImage, int index,
                                                   List<Object> auxiliaryImageVolumes) {
    Map<String, Object> container = new LinkedHashMap<>();
    container.put("name", CommonConstants.COMPATIBILITY_MODE + getName(index));
    container.put(IMAGE, auxiliaryImage.get(IMAGE));
    container.put("command", List.of(AuxiliaryImageConstants.AUXILIARY_IMAGE_INIT_CONTAINER_WRAPPER_SCRIPT));
    container.put("imagePullPolicy", getImagePullPolicy(auxiliaryImage));
    container.put("env", createEnv(auxiliaryImage, auxiliaryImageVolumes, getName(index)));
    container.put(VOLUME_MOUNTS, Arrays.asList(getVolumeMount(auxiliaryImage,
        AuxiliaryImageConstants.AUXILIARY_IMAGE_TARGET_PATH), getScriptsVolumeMount()));
    return container;
  }

  private Object getImagePullPolicy(Map<String, Object> auxiliaryImage) {
    return Optional.ofNullable(auxiliaryImage.get("imagePullPolicy")).orElse(
            CommonUtils.getInferredImagePullPolicy((String) auxiliaryImage.get(IMAGE)));
  }

  private List<Object> createEnv(Map<String, Object> auxiliaryImage, List<Object> auxiliaryImageVolumes, String name) {
    List<Object> vars = new ArrayList<>();
    addEnvVar(vars, AuxiliaryImageEnvVars.AUXILIARY_IMAGE_PATH, getMountPath(auxiliaryImage, auxiliaryImageVolumes));
    addEnvVar(vars, AuxiliaryImageEnvVars.AUXILIARY_IMAGE_TARGET_PATH,
        AuxiliaryImageConstants.AUXILIARY_IMAGE_TARGET_PATH);
    addEnvVar(vars, AuxiliaryImageEnvVars.AUXILIARY_IMAGE_COMMAND, getCommand(auxiliaryImage));
    addEnvVar(vars, AuxiliaryImageEnvVars.AUXILIARY_IMAGE_CONTAINER_IMAGE, (String)auxiliaryImage.get(IMAGE));
    addEnvVar(vars, AuxiliaryImageEnvVars.AUXILIARY_IMAGE_CONTAINER_NAME, CommonConstants.COMPATIBILITY_MODE + name);
    return vars;
  }

  private String getCommand(Map<String, Object> auxiliaryImage) {
    return Optional.ofNullable((String) auxiliaryImage.get("command"))
            .orElse(AuxiliaryImageConstants.AUXILIARY_IMAGE_DEFAULT_INIT_CONTAINER_COMMAND);
  }

  private String getName(int index) {
    return AuxiliaryImageConstants.AUXILIARY_IMAGE_INIT_CONTAINER_NAME_PREFIX + (index + 1);
  }

  private void addEnvVar(List<Object> vars, String name, String value) {
    Map<String, String> envVar = new LinkedHashMap<>();
    envVar.put("name", name);
    envVar.put("value", value);
    vars.add(envVar);
  }

  private void adjustReplicasDefault(Map<String, Object> spec, String apiVersion) {
    if (CommonConstants.API_VERSION_V8.equals(apiVersion)) {
      spec.putIfAbsent("replicas", 0);
    }
  }

  private void convertServerStartState(Map<String, Object> spec, Map<String, Object> toBePreserved) {
    removeAndPreserveServerStartState(spec, toBePreserved, "$.spec");
    Optional.ofNullable(getAdminServer(spec)).ifPresent(
        as -> removeAndPreserveServerStartState(as, toBePreserved, "$.spec.adminServer"));
    Optional.ofNullable(getClusters(spec)).ifPresent(cl -> cl.forEach(cluster ->
        removeAndPreserveServerStartStateForCluster((Map<String, Object>) cluster, toBePreserved)));
    Optional.ofNullable(getManagedServers(spec)).ifPresent(ms -> ms.forEach(managedServer ->
        removeAndPreserveServerStartStateForManagedServer((Map<String, Object>) managedServer, toBePreserved)));
  }

  private void removeAndPreserveServerStartStateForCluster(Map<String, Object> cluster,
                                                 Map<String, Object> toBePreserved) {
    Object name = cluster.get("clusterName");
    if (name != null) {
      removeAndPreserveServerStartState(cluster, toBePreserved, "$.spec.clusters[?(@.clusterName=='" + name + "')]");
    }
  }

  private void removeAndPreserveServerStartStateForManagedServer(Map<String, Object> managedServer,
                                                           Map<String, Object> toBePreserved) {
    Object name = managedServer.get("serverName");
    if (name != null) {
      removeAndPreserveServerStartState(
          managedServer, toBePreserved, "$.spec.managedServer[?(@.serverName=='" + name + "')]");
    }
  }

  private void removeAndPreserveServerStartState(Map<String, Object> spec,
                                                 Map<String, Object> toBePreserved, String scope) {
    Object existing = spec.remove("serverStartState");
    if (existing != null) {
      toBePreserved.put(scope, Map.of("serverStartState", existing));
    }
  }

  private void preserve(Map<String, Object> domain, Map<String, Object> toBePreserved, String apiVersion)
      throws IOException {
    if (!toBePreserved.isEmpty() && API_VERSION_V8.equals(apiVersion) && !API_VERSION_V8.equals(targetAPIVersion)) {
      Map<String, Object> meta = getMetadata(domain);
      Map<String, Object> labels = (Map<String, Object>) meta.computeIfAbsent("labels", k -> new LinkedHashMap<>());
      labels.put("weblogic.v8.preserved", new ObjectMapper().writeValueAsString(toBePreserved));
    }
  }

  @SuppressWarnings("java:S112")
  private void restore(Map<String, Object> domain) {
    if (API_VERSION_V8.equals(targetAPIVersion)) {
      Optional.ofNullable(getMetadata(domain))
          .map(meta -> (Map<String, Object>) meta.get("labels"))
          .map(meta -> (String) meta.remove("weblogic.v8.preserved"))
          .ifPresent(labelValue -> {
            try {
              restore(domain, new ObjectMapper().readValue(labelValue, new TypeReference<>(){}));
            } catch (JsonProcessingException e) {
              throw new RuntimeException(e);
            }
          });
    }
  }

  private void restore(Map<String, Object> domain, Map<String, Object> toBeRestored) {
    if (toBeRestored != null && !toBeRestored.isEmpty()) {
      ReadContext context = JsonPath.parse(domain);
      toBeRestored.entrySet().forEach(entry -> {
        JsonPath path = JsonPath.compile(entry.getKey());
        Optional.ofNullable(read(context, path)).map(List::stream)
            .ifPresent(stream -> stream.forEach(item -> item.putAll((Map<String, Object>) entry.getValue())));
      });
    }
  }

  private List<Map<String, Object>> read(ReadContext context, JsonPath path) {
    Object value = context.read(path);
    if (value instanceof List) {
      return (List<Map<String, Object>>) value;
    }

    return List.of((Map<String, Object>) value);
  }
}
