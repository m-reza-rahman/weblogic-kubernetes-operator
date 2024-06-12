/*
# Copyright (c) 2024, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
*/
resource "oci_file_storage_export" "okemar_export1" {
  #Required
  export_set_id  = oci_file_storage_export_set.okemar_export_set.id
  file_system_id = oci_file_storage_file_system.okemar_fs1.id
  path           = "/${var.cluster_name}okemar1"
}
resource "oci_file_storage_export" "okemar_export2" {
  #Required
  export_set_id  = oci_file_storage_export_set.okemar_export_set.id
  file_system_id = oci_file_storage_file_system.okemar_fs2.id
  path           = "/${var.cluster_name}okemar2"
}
