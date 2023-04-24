#!/bin/bash
# Copyright (c) 2023, Oracle and/or its affiliates.

## Begin WKO IT specific 
KUBERNETES_CLI=${KUBERNETES_CLI:-kubectl}
test_filter="**/It*"
parallel_run="false"
threads="2"
wdt_download_url="https://github.com/oracle/weblogic-deploy-tooling/releases/latest"
wit_download_url="https://github.com/oracle/weblogic-image-tool/releases/latest"
wle_download_url="https://github.com/oracle/weblogic-logging-exporter/releases/latest"
maven_profile_name="v8o"
skip_tests=false

# usage - display the help for this script
function usage {
  echo "usage: ${script} [-v <version>] [-n <name>] [-s] [-o <directory>] [-t <tests>] [-c <name>] [-p true|false] [-x <number_of_threads>] [-d <wdt_download_url>] [-i <wit_download_url>] [-l <wle_download_url>] [-m <maven_profile_name>] [-h]"
  echo "  -v Kubernetes version (optional) "
  echo "      (default: 1.21, supported values depend on the kind version. See kindversions.properties) "
  echo "  -s Skip tests. If this option is specified then the cluster is created and verrazzano is installed, but no tests are run. "
  echo "  -o Output directory (optional) "
  echo "      (default: \${WORKSPACE}/logdir/\${BUILD_TAG}, if \${WORKSPACE} defined, else /scratch/\${USER}/kindtest) "
  echo "  -t Test filter (optional) "
  echo "      (default: **/It*) "
  echo "  -p Run It classes in parallel"
  echo "      (default: false) "
  echo "  -x Number of threads to run the classes in parallel"
  echo "      (default: 2) "
  echo "  -d WDT download URL"
  echo "      (default: https://github.com/oracle/weblogic-deploy-tooling/releases/latest) "
  echo "  -i WIT download URL"
  echo "      (default: https://github.com/oracle/weblogic-image-tool/releases/latest) "
  echo "  -l WLE download URL"
  echo "      (default: https://github.com/oracle/weblogic-logging-exporter/releases/latest) "
  echo "  -m Run integration-tests or the other maven_profile_name in the pom.xml"
  echo "      (default: integration-tests) "
  echo "  -h Help"
  exit 1
}

while getopts "v:s:t:x:p:d:i:l:m:h" opt; do
  case $opt in
    v) k8s_version="${OPTARG}"
    ;;
    s) skip_tests=true
    ;;
    t) test_filter="${OPTARG}"
    ;;
    x) threads="${OPTARG}"
    ;;
    p) parallel_run="${OPTARG}"
    ;;
    d) wdt_download_url="${OPTARG}"
    ;;
    i) wit_download_url="${OPTARG}"
    ;;
    l) wle_download_url="${OPTARG}"
    ;;
    m) maven_profile_name="${OPTARG}"
    ;;
    h) usage 0
    ;;
    *) usage 1
    ;;
  esac
done

## End WKO IT specific
./vzsetup.sh -k "$k8s_version"
if [[ $? -ne 0 ]]; then
  exit
fi

if [ "$skip_tests" = true ] ; then
  echo 'Cluster created and Verrazzano installed. Skipping tests.'
  exit 0
fi

## WKO IT specific 
if [[ -z "${WORKSPACE}" ]]; then
  outdir="/scratch/${USER}/kindtest"
else
  outdir="${WORKSPACE}/logdir/${BUILD_TAG}"
fi


mkdir -m777 -p "${outdir}"
export RESULT_ROOT="${outdir}/wl_k8s_test_results"
if [ -d "${RESULT_ROOT}" ]; then
  rm -Rf "${RESULT_ROOT}/*"
else
  mkdir -m777 "${RESULT_ROOT}"
fi

echo "Results will be in ${RESULT_ROOT}"

export PV_ROOT="${outdir}/k8s-pvroot"
if [ -d "${PV_ROOT}" ]; then
  rm -Rf "${PV_ROOT}/*"
else
  mkdir -m777 "${PV_ROOT}"
fi

echo "Persistent volume files, if any, will be in ${PV_ROOT}"

echo 'Clean up result root...'
rm -rf "${RESULT_ROOT:?}/*"


# If IT_TEST is set, integration-test profile is used to run the tests and 
# MAVEN_PROFILE_NAME parameter is ignored

# If a specific maven profile is chosen, all tests are run with the chosen 
# profile and IT_TEST parameter is ignored

echo "Run tests..."
if [ "${test_filter}" != "**/It*" ]; then
  echo "Overriding the profile to integration-test"
  echo "Running mvn -Dit.test=${test_filter} -Dwdt.download.url=${wdt_download_url} -Dwit.download.url=${wit_download_url} -Dwle.download.url=${wle_download_url} -DPARALLEL_CLASSES=${parallel_run} -DNUMBER_OF_THREADS=${threads}  -pl integration-tests -P integration-tests verify"
  time mvn -Dit.test="${test_filter}" -Dwdt.download.url="${wdt_download_url}" -Dwit.download.url="${wit_download_url}" -Dwle.download.url="${wle_download_url}" -DPARALLEL_CLASSES="${parallel_run}" -DNUMBER_OF_THREADS="${threads}" -pl integration-tests -P integration-tests verify 2>&1 | tee "${RESULT_ROOT}/kindtest.log" || captureLogs
else
    echo "Running Verrazzano Integration tests with profile  [${maven_profile_name}]"
    echo "Running mvn -Dwdt.download.url=${wdt_download_url} -Dwit.download.url=${wit_download_url} -Dwle.download.url=${wle_download_url} -DPARALLEL_CLASSES=${parallel_run} -DNUMBER_OF_THREADS=${threads} -pl integration-tests -P ${maven_profile_name} verify"
    time mvn -Dwdt.download.url="${wdt_download_url}" -Dwit.download.url="${wit_download_url}" -Dwle.download.url="${wle_download_url}" -DPARALLEL_CLASSES="${parallel_run}" -DNUMBER_OF_THREADS="${threads}" -pl integration-tests -P ${maven_profile_name} verify 2>&1 | tee "${RESULT_ROOT}/kindtest.log" || captureLogs
fi

echo "Collect journalctl logs"
${WLSIMG_BUILDER:-docker} ps
${WLSIMG_BUILDER:-docker} exec admin-control-plane journalctl --utc --dmesg --system > "${RESULT_ROOT}/journalctl-admin-control-plane.out"

## End WKO IT specific 
