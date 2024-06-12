# Copyright (c) 2024, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

# Template to generate TF variables file for cluster creation from property file oci.props
# 
# User-specific vars - you can get these easily from the OCI console from your user page
#

# OCID can be obtained from the user info page in the OCI console
user_ocid="@USEROCID@"
# API key fingerprint and private key location, needed for API access -- you should have added a public API key through the OCI console first
fingerprint="@OCIAPIPUBKEYFINGERPRINT@" 
private_key_path="@OCIPRIVATEKEYPATH@"

# Required tenancy vars
tenancy_ocid="@TENANCYOCID@"
compartment_ocid="@COMPOCID@"
compartment_name="@COMPARTMENTNAME@"
sub_compartment_ocid="@SUBCOMPARTMENTOCID@"
region="@REGION@"

#
# Cluster-specific vars
#

# VCN CIDR -- must be unique within the compartment in the tenancy
# - assuming 1:1 cluster:vcn 
# BE SURE TO SET BOTH VARS -- the first 2 octets for each variable have to match
vcn_cidr_prefix="@VCNCIDRPREFIX@"
vcn_cidr="@VCNCIDR@"
vcn_ocid="@VCNOCID@"

# Subnet

pub_subnet_ocid="@PUBSUBNETOCID@"
private_subnet_ocid="@PRIVATESUBNETOCID@"
# Cluster name and k8s version
cluster_kubernetes_version="@OKEK8SVERSION@"
cluster_name="@OKECLUSTERNAME@"

# Node pool info
node_pool_kubernetes_version="@OKEK8SVERSION@"
node_pool_name="@OKECLUSTERNAME@_workers"
node_pool_node_shape="@NODEPOOLSHAPE@"
node_pool_node_image_name="@NODEPOOLIMAGENAME@"
node_pool_quantity_per_subnet=1

# SSH public key, for SSH access to nodes in the cluster
node_pool_ssh_public_key="@NODEPOOLSSHPUBKEY@"


#####NEWVALUES####
# provider
 api_fingerprint = "@OCIAPIPUBKEYFINGERPRINT@"
#
 api_private_key_path = "@OCIPRIVATEKEYPATH@"
#
 home_region = "@REGION@" # Use short form e.g. ashburn from location column https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm
#
 tenancy_id = "@TENANCYOCID@"
#
 user_id = "@USEROCID@"
#
 cluster_name="@OKECLUSTERNAME@"
 compartment_id = "@COMPOCID@"
 vcn_id = "@VCNOCID@"
 control_plane_subnet_id = "@PUBSUBNETOCID@"
 pub_lb_id = "@PUBSUBNETOCID@"
 worker_subnet_id = "@PRIVATESUBNETOCID@"
#
# # ssh
 ssh_private_key_path = "@NODEPOOLSSHPK@"
 ssh_public_key_path  = "@NODEPOOLSSHPUBKEY@"
#
# # clusters
# ## For regions, # Use short form e.g. ashburn from location column https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm
# ## VCN, Pods and services clusters must not overlap with each other and with those of other clusters.
 clusters = {
   c1 = { region = "@REGION@", vcn = "10.1.0.0/16", pods = "10.201.0.0/16", services = "10.101.0.0/16", enabled = true }
     }
#
     kubernetes_version = "v@OKEK8SVERSION@"
#
     cluster_type = "basic"
#
     oke_control_plane = "public"
     node_shape = "@NODEPOOLSHAPE@"
nodepools = {
  np1 = {
    shape            = "@NODEPOOLSHAPE@",
    ocpus            = 2,
    memory           = 64,
    size             = 2,
    boot_volume_size = 150,
  }
}

