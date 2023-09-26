cat >domain-resource.yaml <<EOF
# Copyright (c) 2023, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.
#
# This is an example of how to define a Domain resource.
#
apiVersion: "weblogic.oracle/v9"
kind: Domain
metadata:
  name: ${domainUID}
  namespace: default
  labels:
    weblogic.domainUID: ${domainUID}

spec:
  # Set to 'PersistentVolume' to indicate 'Domain on PV'.
  domainHomeSourceType: PersistentVolume

  # The WebLogic Domain Home, this must be a location within
  # the persistent volume for 'Domain on PV' domains.
  domainHome: /shared/domains/${domainUID}

  # The WebLogic Server image that the Operator uses to start the domain
  # **NOTE**:
  # This sample uses General Availability (GA) images. GA images are suitable for demonstration and
  # development purposes only where the environments are not available from the public Internet;
  # they are not acceptable for production use. In production, you should always use CPU (patched)
  # images from OCR or create your images using the WebLogic Image Tool.
  # Please refer to the "OCR" and "WebLogic Images" pages in the WebLogic Kubernetes Operator
  # documentation for details.
  image: "container-registry.oracle.com/middleware/weblogic:12.2.1.4"

  # Defaults to "Always" if image tag (version) is ':latest'
  imagePullPolicy: IfNotPresent

  # Identify which Secret contains the credentials for pulling an image
  imagePullSecrets:
    - name: ${NAME_PREFIX}regcred

  # Identify which Secret contains the WebLogic Admin credentials,
  # the secret must contain 'username' and 'password' fields.
  webLogicCredentialsSecret:
    name: ${domainUID}-weblogic-credentials

  # Whether to include the WebLogic Server stdout in the pod's stdout, default is true
  includeServerOutInPodLog: true

  # Whether to enable overriding your log file location, defaults to 'True'. See also 'logHome'.
  #logHomeEnabled: false

  # The location for domain log, server logs, server out, introspector out, and Node Manager log files
  # see also 'logHomeEnabled', 'volumes', and 'volumeMounts'.
  #logHome: /shared/logs/sample-${domainUID}
  #
  # Set which WebLogic Servers the Operator will start
  # - "Never" will not start any server in the domain
  # - "AdminOnly" will start up only the administration server (no managed servers will be started)
  # - "IfNeeded" will start all non-clustered servers, including the administration server, and clustered servers up to their replica count.
  serverStartPolicy: IfNeeded

  configuration:
    # Settings for initializing the domain home on 'PersistentVolume'
    initializeDomainOnPV:

      # Settings for domain home on PV.
      domain:
        # Valid model domain types are 'WLS', and 'JRF', default is 'JRF'
        domainType: WLS

        # Domain creation image(s) containing WDT model, archives, and install.
        #   "image"                - Image location
        #   "imagePullPolicy"      - Pull policy, default "IfNotPresent"
        #   "sourceModelHome"      - Model file directory in image, default "/auxiliary/models".
        #   "sourceWDTInstallHome" - WDT install directory in image, default "/auxiliary/weblogic-deploy".
        domainCreationImages:
        - image: "${Domain_Creation_Image_tag}"
          imagePullPolicy: IfNotPresent
          #sourceWDTInstallHome: /auxiliary/weblogic-deploy
          #sourceModelHome: /auxiliary/models

        # Optional configmap for additional models and variable files
        #domainCreationConfigMap: sample-${domainUID}-wdt-config-map

    # Secrets that are referenced by model yaml macros
    # (the model yaml in the optional configMap or in the image)
    #secrets:
    #- sample-${domainUID}-datasource-secret

  # Settings for all server pods in the domain including the introspector job pod
  serverPod:
    # Optional new or overridden environment variables for the domain's pods
    # - This sample uses CUSTOM_DOMAIN_NAME in its image model file
    #   to set the WebLogic domain name
    env:
    - name: CUSTOM_DOMAIN_NAME
      value: ${domainUID}
    - name: JAVA_OPTIONS
      value: "-Dweblogic.StdoutDebugEnabled=false"
    - name: USER_MEM_ARGS
      value: "-Djava.security.egd=file:/dev/./urandom -Xms256m -Xmx512m "
    resources:
      requests:
        cpu: "250m"
        memory: "768Mi"

    # Volumes and mounts for hosting the domain home on PV and domain's logs. See also 'logHome'.
    volumes:
    - name: weblogic-domain-storage-volume
      persistentVolumeClaim:
        claimName: wls-azurefile-${TIMESTAMP}
    volumeMounts:
    - mountPath: /shared
      name: weblogic-domain-storage-volume

  # The desired behavior for starting the domain's administration server.
  # adminServer:
    # Setup a Kubernetes node port for the administration server default channel
    #adminService:
    #  channels:
    #  - channelName: default
    #    nodePort: 30701

  # The number of managed servers to start for unlisted clusters
  replicas: 3

  # The name of each Cluster resource
  clusters:
  - name: sample-${domainUID}-cluster-1

  # Change the restartVersion to force the introspector job to rerun
  # to force a roll of your domain's WebLogic Server pods.
  restartVersion: '1'

  # Changes to this field cause the operator to repeat its introspection of the
  #  WebLogic domain configuration.
  introspectVersion: '1'

---

apiVersion: "weblogic.oracle/v1"
kind: Cluster
metadata:
  name: sample-${domainUID}-cluster-1
  # Update this with the namespace your domain will run in:
  namespace: default
  labels:
    # Update this with the "domainUID" of your domain:
    weblogic.domainUID: ${domainUID}
spec:
  # This must match a cluster name that is  specified in the WebLogic configuration
  clusterName: cluster-1
  # The number of managed servers to start for this cluster
  replicas: 2


EOF

cat >admin-lb.yaml <<EOF
apiVersion: v1
kind: Service
metadata:
  name: ${domainUID}-admin-server-external-lb
  namespace: default
spec:
  ports:
  - name: default
    port: 7001
    protocol: TCP
    targetPort: 7001
  selector:
    weblogic.domainUID: ${domainUID}
    weblogic.serverName: admin-server
  sessionAffinity: None
  type: LoadBalancer

EOF

cat >cluster-lb.yaml <<EOF
apiVersion: v1
kind: Service
metadata:
  name: ${domainUID}-cluster-1-lb
  namespace: default
spec:
  ports:
  - name: default
    port: 8001
    protocol: TCP
    targetPort: 8001
  selector:
    weblogic.domainUID: ${domainUID}
    weblogic.clusterName: cluster-1
  sessionAffinity: None
  type: LoadBalancer

EOF
