# !/bin/bash
# Copyright (c) 2023 Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

set -eu

# https://github.com/kubernetes-sigs/kind/releases
# https://kind.sigs.k8s.io/docs/user/quick-start/
# https://kind.sigs.k8s.io/docs/user/quick-start/#configuring-your-kind-cluster

KIND_VERSION=${1:-0.18.0}

rroot='/usr/local/packages/aime/ias/run_as_root' 

echo "Installing kind version [${KIND_VERSION}]"
unset KUBECONFIG
curl -Lo ./kind https://kind.sigs.k8s.io/dl/v${KIND_VERSION}/kind-linux-amd64
chmod +x ./kind
${rroot} "mv kind /bin" 

kv=$(kind version)
echo "Installed Kind Version is [$kv]"
