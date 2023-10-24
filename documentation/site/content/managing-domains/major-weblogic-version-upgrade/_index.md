+++
title = "Upgrade managed domains"
date = 2023-10-05T16:43:45-05:00
weight = 6
pre = "<b> </b>"
description = "Upgrade managed domains to v14.1.2.0."
+++

{{< table_of_contents >}}

This document provides guidelines for upgrading WLS and FMW/JRF infrastructure domains to a higher major version.

### General upgrade procedures

In general, the process for upgrading WLS and FMW/JRF infrastructure domains in Kubernetes is similar to upgrading domains on premises. For a thorough understanding, we suggest that you read the [Fusion Middleware Upgrade Guide](https://docs.oracle.com/en/middleware/fusion-middleware/12.2.1.4/asmas/planning-upgrade-oracle-fusion-middleware-12c.html#GUID-D9CEE7E2-5062-4086-81C7-79A33A200080).

Before the upgrade, you must do the following:

- If your [domain home source type]({{< relref "/managing-domains/choosing-a-model/_index.md" >}}) is Domain on Persistent Volume (DoPV), then back up the domain home.
- If your domain type is `JRF`, see [FMW/JRF domain using Model In Image](#fmwjrf-domain-using-model-in-image):
   - Back up the JRF database.
   - Back up the OPSS wallet file. See [Save the OPSS wallet secret](#back-up-the-opss-wallet-and-save-it-in-a-secret).
   - Make sure nothing else is accessing the database.
- **Do not delete** the domain resource.
- Shut down the domain by patching the domain and/or cluster spec `serverStartPolicy` to `Never`. For example:
   ```
   $ kubectl -n sample-domain1-ns patch domain sample-domain1 --type=json -p='[ {"op": "replace", "path": "/spec/serverStartPolicy", "value": "Never"}]'
   ```

If your domain is using Model in Image, because the domain will be rebuilt when the model is updated, see the [Upgrade Use Cases](#upgrade-use-cases) for details.

**Note** If you are using a FMW/JRF domain and upgrading from 12.2.1.3 to 12.2.14, you do not need to run any Upgrade Assistant or Reconfigure domain according to Oracle Doc 2752458.1. You simply have to update the base image with the latest version of FMW/JRF.

#### Back up the OPSS wallet and save it in a secret

The operator provides a helper script, the [OPSS wallet utility](https://orahub.oci.oraclecorp.com/weblogic-cloud/weblogic-kubernetes-operator/-/blob/main/kubernetes/samples/scripts/domain-lifecycle/opss-wallet.sh), for extracting the wallet file and storing it in a Kubernetes `walletFileSecret`. In addition, you should save the wallet file in a safely backed-up location, outside of Kubernetes. For example, the following command saves the OPSS wallet for the `sample-domain1` domain in the `sample-ns` namespace to a file named `ewallet.p12` in the `/tmp` directory and also stores it in the wallet secret named `sample-domain1-opss-walletfile-secret`.

```
$ opss-wallet.sh -n sample-ns -d sample-domain1 -s -r -wf /tmp/ewallet.p12 -ws sample-domain1-opss-walletfile-secret
```

### Upgrade use cases

Consider the following use case scenarios, depending on your WebLogic domain type (`WLS` or `JRF`) and domain home source type (Domain on PV or Model in Image).

- [WLS Domain on Persistent Volume](#wls-domain-on-persistent-volume)
- [FMW/JRF Domain on Persistent Volume](#fmwjrf-domain-on-persistent-volume)
- [WLS domain using Model in Image](#wls-domain-using-model-in-image)
- [FMW/JRF domain using Model in Image](#fmwjrf-domain-using-model-in-image)

#### WLS Domain on Persistent Volume

1. Follow the steps in the [General upgrade procedures](#general-upgrade-procedures).  You can skip the database related steps.
2. Update the domain resource to use the new WebLogic version base image, and patch the `serverStartPolicy` to `IfNeeded` to restart the domain.  For example,
   `kubectl -n sample-domain1-ns patch domain sample-domain1 --type=json -p='[ {"op": "replace", "path": "/spec/serverStartPolicy", "value": "Never"}, {"op": "replace", "path":"/spec/image", "value":"<New WebLogic version base image>"]'`

#### FMW/JRF Domain on Persistent Volume

1. Follow the steps in the [General upgrade procedures](#general-upgrade-procedures).
2. Update the domain resource to use the Fusion Middleware Infrastructure version base image, and patch the `serverStartPolicy` to `IfNeeded` to restart the domain.  For example,
   `kubectl -n sample-domain1-ns patch domain sample-domain1 --type=json -p='[ {"op": "replace", "path": "/spec/serverStartPolicy", "value": "Never"}, {"op": "replace", "path":"/spec/image", "value":"<New Fusion Middleware Infrastructure version image>"]'`

#### WLS domain using Model in Image

1. Follow the steps in the [General upgrade procedures](#general-upgrade-procedures).
2. You can use this patch command for redeploying the domain with the new WebLogic version base image.  For example,
   `kubectl -n sample-domain1-ns patch domain sample-domain1 --type=json -p='[ {"op": "replace", "path": "/spec/serverStartPolicy", "value": "Never"}, {"op": "replace", "path":"/spec/image", "value":"<New WebLogic version base image>"]'`

#### FMW/JRF domain using Model in Image

FMW/JRF domains using Model in Image has been deprecated since WebLogic Kubernetes Operator 4.1.  We recommend moving your domain home to Domain on Persistent Volume. For more information, see [Domain On Persistent Volume]({{< relref "/managing-domains/domain-on-pv/overview.md" >}}).

If you cannot move the domain to Persistent Volume at the moment, you can use simple procedures outlined in [Here](#fmwjrf-domain-on-persistent-volume).

1. Follow the steps in the [General upgrade procedures](#general-upgrade-procedures).
2. If are not using an auxiliary image in your domain, then create a [Domain creation image]({{< relref "/managing-domains/domain-on-pv/domain-creation-images.md" >}}).
3. Create a new domain resource YAML file.  You should have at least the following changes:

```
# Change type to PersistentVolume
domainHomeSourceType: PersistentVolume
image: <Fusion Middleware Infrastructure 14120 base image>
...
serverPod:
    ...
    # specify the volume and volume mount information

    volumes:
    - name: weblogic-domain-storage-volume
      persistentVolumeClaim:
         claimName: sample-domain1-pvc-rwm1
    volumeMounts:
    - mountPath: /share
      name: weblogic-domain-storage-volume

  # specify a new configuration section, remove the old configuration section.

  configuration:

    # secrets that are referenced by model yaml macros
    # sample-domain1-rcu-access is used for JRF domains
    secrets: [ sample-domain1-rcu-access ]

    initializeDomainOnPV:
      persistentVolumeClaim:
        metadata:
            name: sample-domain1-pvc-rwm1
        spec:
            storageClassName: my-storage-class
            resources:
                requests:
                    storage: 10Gi
      domain:
          createIfNotExists: Domain
          domainCreationImages:
          - image: 'myaux:v6'
          domainType: JRF
          domainCreationConfigMap: sample-domain1-wdt-config-map
          opss:
            # Make sure you have already saved the wallet file secret. This allows the domain to use 
            # an existing JRF database schemas.
            walletFileSecret: sample-domain1-opss-walletfile-secret
            walletPasswordSecret: sample-domain1-opss-wallet-password-secret
```

4. Deploy the domain. If it is successful, then the domain has been migrated to a persistent volume.  Now, you can proceed to upgrade to version 14.1.2.0, see [FMW/JRF domain on PV](#fmwjrf-domain-on-persistent-volume).

