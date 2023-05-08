#!/bin/bash
# Copyright (c) 2023 Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

set -eu

KUBERNETES_CLI=${KUBERNETES_CLI:-kubectl}

# https://www.downloadkubernetes.com/
version=${1:-1.26.3}
rroot="/usr/local/packages/aime/ias/run_as_root"

$rroot "curl -L --retry 3 http://storage.googleapis.com/kubernetes-release/release/v${version}/bin/linux/amd64/${KUBERNETES_CLI} -o /bin/${KUBERNETES_CLI} && chmod +x /bin/${KUBERNETES_CLI}"

kv=$(${KUBERNETES_CLI} version -o json|jq -rj '.clientVersion|.gitVersion')

echo "Installed Kubernate Client version is [$kv]"
