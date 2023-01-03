# Copyright (c) 2017, 2022, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
#
# HOW TO BUILD THIS IMAGE
# -----------------------
# Run:
#      $ ./buildDockerImage.sh [-t <image-name>]
#
# -------------------------
FROM container-registry.oracle.com/java/jdk:19.0.1-oraclelinux8

LABEL "org.opencontainers.image.authors"="Ryan Eberhard <ryan.eberhard@oracle.com>" \
      "org.opencontainers.image.url"="https://github.com/oracle/weblogic-kubernetes-operator" \
      "org.opencontainers.image.source"="https://github.com/oracle/weblogic-kubernetes-operator" \
      "org.opencontainers.image.vendor"="Oracle Corporation" \
      "org.opencontainers.image.title"="Oracle WebLogic Server Kubernetes Operator" \
      "org.opencontainers.image.description"="Oracle WebLogic Server Kubernetes Operator" \
      "org.opencontainers.image.documentation"="https://oracle.github.io/weblogic-kubernetes-operator/"

ENV LANG="en_US.UTF-8"

# Install Java and make the operator run with a non-root user id (1000 is the `oracle` user)
RUN set -eux; \
    dnf -y update; \
    dnf -y install jq; \
    dnf clean all; \
    useradd -d /operator -M -s /bin/bash -g root -u 1000 oracle; \
    mkdir -m 775 /operator; \
    mkdir -m 775 /deployment; \
    mkdir -m 775 /probes; \
    mkdir -m 775 /logs; \
    mkdir /operator/lib; \
    chown -R oracle:root /operator /deployment /probes /logs

USER oracle

COPY --chown=oracle:root operator/scripts/* /operator/
COPY --chown=oracle:root deployment/scripts/* /deployment/
COPY --chown=oracle:root probes/scripts/* /probes/
COPY --chown=oracle:root operator/target/weblogic-kubernetes-operator.jar /operator/weblogic-kubernetes-operator.jar
COPY --chown=oracle:root operator/target/lib/*.jar /operator/lib/

HEALTHCHECK --interval=1m --timeout=10s \
  CMD /probes/livenessProbe.sh

WORKDIR /deployment/

CMD ["/deployment/operator.sh"]
