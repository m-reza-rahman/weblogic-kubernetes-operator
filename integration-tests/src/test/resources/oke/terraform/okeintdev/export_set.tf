/*
# Copyright (c) 2023, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
*/

resource "oci_file_storage_export_set" "oketest_export_set" {
  # Required
  mount_target_id = ${var.mount_target_ocid}
}

