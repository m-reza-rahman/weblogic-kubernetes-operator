# Sample to create an OKE cluster using Terraform scripts

The provided sample will create:

* A new Virtual Cloud Network (VCN) for the cluster
* Two LoadBalancer subnets with security lists
* Three Worker subnets with security lists
* A Kubernetes Cluster with one Node Pool
* A `kubeconfig` file to allow access using `kubectl`

Nodes and network settings will be configured to allow SSH access, and the cluster networking policies will allow `NodePort` services to be exposed. This cluster can be used for testing and development purposes only. The provided samples of Terraform scripts should not be considered for creating production clusters, without more of a review.

All OCI Container Engine masters are Highly Available (HA) and fronted by load balancers.



## Prerequisites

To use these Terraform scripts, you will need fulfill the following prerequisites:
* Have an existing tenancy with enough compute and networking resources available for the desired cluster.
* Have an [Identity and Access Management](https://docs.cloud.oracle.com/iaas/Content/ContEng/Concepts/contengpolicyconfig.htm#PolicyPrerequisitesService) policy in place within that tenancy to allow the OCI Container Engine for Kubernetes service to manage tenancy resources.
* Have a user defined within that tenancy.
* Have an API key defined for use with the OCI API, as documented [here](https://docs.cloud.oracle.com/iaas/Content/Identity/Tasks/managingcredentials.htm).
* Have an [SSH key pair](https://docs.oracle.com/en/cloud/iaas/compute-iaas-cloud/stcsg/generating-ssh-key-pair.html) for configuring SSH access to the nodes in the cluster.

Create a directory `myterraformscripts`

Copy template.tfvars to `myterraformscripts`

Copy *.sh to `myterraformscripts`

Copy provided `oci.props.template` file to `oci.props` in `myterraformscripts` and add all required values:
* `user.ocid` - OCID for the tenancy user - can be obtained from the user settings in the OCI console.
* `tfvars.filename` - File name for generated tfvar file.
* `okeclustername` - The name for OCI Container Engine for Kubernetes cluster.
* `tenancy.ocid` - OCID for the target tenancy.
* `region` - name of region in the target tenancy.
* `compartment.ocid` - OCID for the target compartment. To find the OCID of the compartment - https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/contactingsupport_topic-Finding_the_OCID_of_a_Compartment.htm
* `compartment.name` - Name for the target compartment.
* `ociapi.pubkey.fingerprint` - Fingerprint of the OCI user's public key.
* `ocipk.path` - API Private Key -- local path to the private key for the API key pair.
* `vcn.cidr.prefix` - Prefix for VCN CIDR, used when creating subnets -- you should examine the target compartment find a CIDR that is available.
* `vcn.cidr` - Full CIDR for the VCN, must be unique within the compartment, first 2 octets should match the vcn_cidr_prefix.
* `nodepool.shape` - A valid OCI VM Shape for the cluster nodes.
* `k8s.version` - Kubernetes version.
* `nodepool.ssh.pubkey` - SSH public key (key contents as a string).
* `nodepool.imagename` - A valid image name for Node Pool creation.
* `terraform.installdir` - Location to install Terraform binaries.

Copy integration-tests/src/test/resources/oke/terraform/*.tf to `myterraformscripts` directory

Optional, to modify the shape of the node, edit node-pool.tf 
```aidl
  node_shape_config {
        #Optional
        memory_in_gbs = 48.0
        ocpus = 4.0
  }
```
Optional, to add more nodes to the cluster(by default 2 worker nodes are created)
modify vcn.tf to add worker subnets
```aidl
resource "oci_core_subnet" "oke-subnet-worker-3" {
  availability_domain = data.oci_identity_availability_domains.ADs.availability_domains[2]["name"]
  cidr_block          = "${var.vcn_cidr_prefix}.12.0/24"
  display_name        = "${var.cluster_name}-WorkerSubnet03"
  dns_label           = "workers03"
  compartment_id      = var.compartment_ocid
  vcn_id              = oci_core_virtual_network.oke-vcn.id
  security_list_ids   = [oci_core_security_list.oke-worker-security-list.id]
  route_table_id      = oci_core_virtual_network.oke-vcn.default_route_table_id
  dhcp_options_id     = oci_core_virtual_network.oke-vcn.default_dhcp_options_id
}
```
Add corresponding egress_security_rules and ingress_security_rules for the worker subnets
```aidl
 egress_security_rules {
    destination = "${var.vcn_cidr_prefix}.12.0/24"
    protocol    = "all"
    stateless   = true
  }
```
```aidl
  ingress_security_rules {
    stateless = true
    protocol  = "all"
    source    = "${var.vcn_cidr_prefix}.12.0/24"
  }
```
Modify node-pool.tf `subnet_ids` to add new worker subnets to the pool
```aidl
subnet_ids = [oci_core_subnet.oke-subnet-worker-1.id, oci_core_subnet.oke-subnet-worker-2.id, oci_core_subnet.oke-subnet-worker-3.id]
```

To run the script, use the command:
```shell
$ kubernetes/samples/scripts/terraform/oke.create.sh oci.props
```
The script collects the values from `oci.props` file and performs the following steps:
* Creates a new tfvars file based on the values from the provided `oci.props` file.
* Downloads and installs all needed binaries for Terraform, Terraform OCI Provider, based on OS system (macOS or Linux)
* Applies the configuration and creates OKE Cluster using Terraform


