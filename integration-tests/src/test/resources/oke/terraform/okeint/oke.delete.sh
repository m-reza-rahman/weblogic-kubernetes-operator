#!/bin/bash
# Copyright (c) 2023, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
#
# This script deletes provisioned OKE Kubernetes cluster using terraform (https://www.terraform.io/)
#

set -o errexit
set -o pipefail

prop() {
  grep "${1}" ${oci_property_file}| grep -v "#" | cut -d'=' -f2
}

cleanupLB() {
  echo 'Clean up left over LB'
  myvcn_id=`oci network vcn list --compartment-id $compartment_ocid  --display-name=${clusterName}_vcn | jq -r '.data[] | .id'`
  declare -a vcnidarray
  vcnidarray=(${myvcn_id// /})
  myip=`oci lb load-balancer list --compartment-id $compartment_ocid |jq -r '.data[] | .id'`
  mysubnets=`oci network subnet list --vcn-id=${vcnidarray[0]} --display-name=${clusterName}-LB-${1} --compartment-id $compartment_ocid | jq -r '.data[] | .id'`

  declare -a iparray
  declare -a mysubnetsidarray
  mysubnetsidarray=(${mysubnets// /})

  iparray=(${myip// /})
  vcn_cidr_prefix=$(prop 'vcn.cidr.prefix')
  for k in "${mysubnetsidarray[@]}"
    do
      for i in "${iparray[@]}"
         do
            lb=`oci lb load-balancer get --load-balancer-id=$i`
            echo "deleting lb with id $i   $lb"
            if [[ (-z "${lb##*$vcn_cidr_prefix*}") || (-z "${lb##*$k*}") ]] ;then
               echo "deleting lb with id $i"
               sleep 60
               oci lb load-balancer delete --load-balancer-id=$i --force || true
            fi
        done
    done
  myip=`oci lb load-balancer list --compartment-id $compartment_ocid |jq -r '.data[] | .id'`
  iparray=(${myip// /})
   for k in "${mysubnetsidarray[@]}"
      do
        for i in "${iparray[@]}"
           do
              lb=`oci lb load-balancer get --load-balancer-id=$i`
              echo "deleting lb with id $i   $lb"
              if [[ (-z "${lb##*$vcn_cidr_prefix*}") || (-z "${lb##*$k*}") ]] ;then
                 echo "deleting lb with id $i"
                 sleep 60
                 oci lb load-balancer delete --load-balancer-id=$i --force || true
              fi
          done
      done
}

deleteOKE() {
  cd ${terraform_script_dir}
  terraform init -var-file=${terraform_script_dir}/${clusterName}.tfvars
  terraform plan -var-file=${terraform_script_dir}/${clusterName}.tfvars
  terraform destroy -auto-approve -var-file=${terraform_script_dir}/${clusterName}.tfvars
}

cleanMT() {
    test_compartment_ocid=${$compartment_ocid}
    echo "oci fs mount-target  list --compartment-id=${test_compartment_ocid}  --display-name=${clusterName}-mt --availability-domain=${availability_domain} | jq -r '.data[] | .\"id\"'"
    mt_ocid=`oci fs mount-target  list --compartment-id=${test_compartment_ocid}  --display-name=${clusterName}-mt --availability-domain=${availability_domain} | jq -r '.data[] | .id'`
    if [ -z "${mt_ocid}" ]; then
       echo "No Mount Target leftover"
    else
        if [ -n "${mt_ocid}" ]; then
            # Check if the mt_ocid is an array
            if [ "$(declare -p mt_ocid 2>/dev/null | grep -o 'declare -a')" == "declare -a" ]; then
                # Delete each element in the array
                for ((i=0; i<${#mt_ocid[@]}; i++)); do
                    element="${mt_ocid[i]}"
                    echo "Deleting $i: $element"
                    oci fs mount-target  delete --mount-target-id=$element --force
                done
                echo "Array of mount target deleted."
            else
                echo "deleting left over mount target."
                oci fs mount-target  delete --mount-target-id=${mt_ocid} --force
            fi
        else
            echo "mount target was deleted."
        fi
    fi
}

#MAIN
oci_property_file=${1:-$PWD/oci.props}
terraform_script_dir=${2:-$PWD}
availability_domain=${3:-mFEn:PHX-AD-1}
clusterName=$(prop 'okeclustername')
compartment_ocid=$(prop 'compartment.ocid')
vcn_cidr_prefix=$(prop 'vcn.cidr.prefix')
export KUBECONFIG=${terraform_script_dir}/${clusterName}_kubeconfig
export PATH=${terraform_script_dir}/terraforminstall:$PATH
echo 'Deleting cluster'
#check and cleanup any left over running Load Balancers
out=$(cleanupLB Subnet01 && :)
echo $out
out=$(cleanupLB Subnet02 && :)
echo $out
deleteOKE || true
cleanMT || true
