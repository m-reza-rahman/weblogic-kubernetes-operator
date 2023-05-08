#!/bin/bash
# Copyright (c) 2023 Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

set -eu

# https://www.downloadkubernetes.com/
version=${1:-1.24.13}
rroot="/usr/local/packages/aime/ias/run_as_root"

$rroot "curl -L --retry 3 http://storage.googleapis.com/kubernetes-release/release/v${version}/bin/linux/amd64/kubectl -o /bin/kubectl && chmod +x /bin/kubectl"

#$rroot "curl -L --retry 3 https://dl.k8s.io/v${version}/bin/darwin/amd64/kubectl -o /bin/kubectl && chmod +x /bin/kubectl"

kv=$(kubectl version -o json|jq -rj '.clientVersion|.gitVersion')

echo "Installed Kubernate Client version is [$kv]"
