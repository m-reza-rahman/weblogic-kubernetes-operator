/*
# Copyright (c)  2024, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
*/
resource "oci_file_storage_mount_target" "oketest_mount_target" {
  #Required
  availability_domain = var.availability_domain

  compartment_id = var.compartment_id
  subnet_id      = var.worker_subnet_id

  #Optional
  display_name = "${var.cluster_name}-mt"
}
