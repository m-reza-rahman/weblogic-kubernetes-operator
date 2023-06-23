+++
title = "JRF domains"
date = 2023-04-26T16:45:16-05:00
weight = 25
pre = "<b> </b>"
description = "Important information about using JRF domains."
+++

{{< table_of_contents >}}

### Overview

A Java Required Files (JRF) domain consists of those components not included in a WebLogic Server
installation that provide common functionality for Oracle business applications and application
frameworks. They consist of a number of independently developed libraries and applications that are
deployed in a common location.

Typically, a JRF domain is used by Fusion Middleware products. The JRF domain has database requirements.
The database components are created using Repository Creation Utility (RCU); a new RCU schema is created before creating a
JRF-based domain.

### Sample WDT model for JRF domain

For the operator to create a JRF domain, your WDT model must have a `RCUDbInfo` section in `domainInfo`.  This is a sample model 
snippet of a typical JRF domain

```yaml
domainInfo:
    RCUDbInfo:
        rcu_prefix: '@@SECRET:@@ENV:DOMAIN_UID@@-rcu-access:rcu_prefix@@'
        rcu_schema_password: '@@SECRET:@@ENV:DOMAIN_UID@@-rcu-access:rcu_schema_password@@'
        rcu_db_conn_string: '@@SECRET:@@ENV:DOMAIN_UID@@-rcu-access:rcu_db_conn_string@@'
        # DBA credentials: required if operator is running the rcu in Domain On PV
        rcu_db_user: '@@SECRET:@@ENV:DOMAIN_UID@@-rcu-access:dba_user@@'
        rcu_admin_password: '@@SECRET:@@ENV:DOMAIN_UID@@-rcu-access:dba_password@@'
```

Refer to [WebLogic Deploy Tooling Connect to a database](https://oracle.github.io/weblogic-deploy-tooling/userguide/database/connect-db/) for more information.

### Importance of domain home directory backup

A JRF domain has a one-to-one relationship with the RCU schema.  After a domain is created using a particular RCU schema,
that schema cannot be reused by another domain and the same schema cannot be shared across different domains.  Any attempts to
create a new domain using the schema that had already been used, will result in an error.

After the domain is created, and the existing schema cannot be reused, it is critical to back up the domain home directory and database.
If the domain home is not properly backed up, and is corrupted or deleted, then you may have to drop the existing RCU schema
and create a new RCU schema before creating the domain again. This applies to potentially losing all existing data.  Therefore, backing up the existing domain home should be
the highest priority in your Kubernetes environment.   

This is especially important for a Domain on PV deployment, where the domain is continually
updated after its initial deployment.  For example, you already deployed new applications, added custom `OPSS` keystores, added `OWSM` policies, and such.
The original models used to create the domain will not match the existing state of the domain (the models are not the source of truth)
and, if you use the original models to create the domain again, then you will lose all the updates that you have made.

#### Download OPSS wallet and store in a Kubernetes secret

After the domain is created, the operator will automatically export the `OPSS wallet` and
store it in an introspector ConfigMap; the name of the ConfigMap follows the pattern `<domain uid>-weblogic-domain-introspect-cm`
with key `ewallet.p12`.  You can export this file and put it in a safe place. We provide a
[OPSS wallet utility](https://orahub.oci.oraclecorp.com/weblogic-cloud/weblogic-kubernetes-operator/-/blob/main/kubernetes/samples/scripts/domain-lifecycle/opss-wallet.sh)
for extracting this file and storing it in a Kubernetes `walletFileSecret`.  You should also save the wallet file in a safely backed up location outside of Kubernetes.

For example,

```
opss-wallet.sh -n sample-ns -d sample-domain1 -s -r -wf /tmp/ewallet.p12 -ws jrf-wallet-file-secret
```

#### Disaster recovery when the domain home directory is destroyed for Domain on PV deployment

When a JRF domain is created, an encryption key `OPSS wallet` is stored in the file system where the domain home resides.
This specific wallet key can be exported and used to create a new domain. But, allowing it to reuse the existing RCU schema,
without this specific wallet key, there is no way to reuse the original RCU schema.  Therefore, you should
back up this encryption key for disaster recovery.

After the operator created the JRF domain, it stores the `OPSS wallet` in a ConfigMap.
You can extract and back it up in a safe location after the domain is created. See [Download and save the OPSS wallet](#download-opss-wallet-and-store-in-a-kubernetes-secret).

In the domain resource YAML file, you can provide two secrets in the `opss` section under `configuration.initializeDomainOnPV.domain`:

```
     ...
     #  For domain on PV, opss settings are under `configuration.initializeDomainOnPV.domain.opss` 
     #  DO NOT specify it under `configuration`
     #
     configuration:
        initializeDomainOnPV:
        ...
           domain:
              createIfNotExists: domain
              domainType: JRF
              ...
              opss:
               walletFileSecret: jrf-wallet-file-secret
               walletPasswordSecret: sample-domain1-opss-wallet-password-secret
```

`walletPasswordSecret` is required during initial deployment.  This secret contains the password used to
encrypt the exported `OPSS wallet`.  You can create a Kubernetes secret with the key `walletPassword` containing the password.
The password must be at least 8 alphanumeric characters with one special character.

In case the domain home directory is corrupted and not recoverable and you need to recreate the domain and reuse the existing RCU schema:

1. Delete the existing domain home directory on PV.
2. Specify the `walletFileSecret` - A Kubernetes secret with the key `walletFile` containing the exported OPSS wallet
   file `ewallet.p12` that you have created earlier.

For example,
```
  kubectl -n sample-ns patch domain sample-domain1 --type='JSON' -p='[ { "op" : "add", "path" : "/spec/configuration/initializeDomainOnPV/domain/opss/walletFileSecret", "value" : "jrf-wallet-file-secret" }]'
```

3. Update the `introspectVersion` in the domain resource.

```
kubectl -n sample-ns patch domain sample-domain1 --type='JSON' -p='[ { "op" : "replace", "path" : "/spec/intropsectVersion", "value" : "15" }]'
```

The operator will create a new domain from the existing WDT models and reuse the original RCU schema. This will **not** create a domain
with all the updates to the domain made after the initial deployment, but you will be able to access the original RCU schema database without
losing all its data.


#### Disaster recovery when the domain home directory is destroyed for Model in Image deployment

Note: JRF support has been deprecated since operator version 4.10, use Domain on PV instead.

When a JRF domain is created, an encryption key `OPSS wallet` is stored in the file system where the domain home resides.
This specific wallet key can be exported and used to create a new domain. But, allowing it to reuse the existing RCU schema,
without this specific wallet key, there is no way to reuse the original RCU schema.  Therefore, you should
back up this encryption key for disaster recovery.

After the operator created the JRF domain, it stores the `OPSS wallet` in a ConfigMap.
You can extract and back it up in a safe location after the domain is created. See [Download and save the OPSS wallet](#download-opss-wallet-and-store-in-a-kubernetes-secret).

In the domain resource YAML file, you can provide two secrets in the `opss` section under `configuration`:

```
     configuration:
        model:
           ...
        opss:
           walletFileSecret: jrf-wallet-file-secret
           walletPasswordSecret: sample-domain1-opss-wallet-password-secret
```

`walletPasswordSecret` is required during initial deployment.  This secret contains the password used to
encrypt the exported `OPSS wallet`.  You can create a Kubernetes secret with the key `walletPassword` containing the password.
The password must be at least 8 alphanumeric characters with one special character.

In case the domain home directory is corrupted and you need to recreate the domain and reuse the existing RCU schema:

1. Specify the `walletFileSecret` - A Kubernetes secret with the key `walletFile` containing the exported OPSS wallet
   file `ewallet.p12` that you have created earlier.

For example,
```
  kubectl -n sample-ns patch domain sample-domain1 --type='JSON' -p='[ { "op" : "add", "path" : "/spec/configuration/initializeDomainOnPV/domain/opss/walletFileSecret", "value" : "jrf-wallet-file-secret" }]'
```

2. Update the `introspectVersion` in the domain resource.

```
kubectl -n sample-ns patch domain sample-domain1 --type='JSON' -p='[ { "op" : "replace", "path" : "/spec/intropsectVersion", "value" : "15" }]'
```

The operator will create a new domain from the existing WDT models and reuse the original RCU schema. 
