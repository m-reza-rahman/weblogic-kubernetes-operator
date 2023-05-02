/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates.
 * Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
 * This example file shows how to configure the oci provider to target the a single region.
*/

// These variables would commonly be defined as environment variables or sourced in a .env file

variable "region" {
  default = "us-phoenix-1"
}

variable "tenancy_ocid" {
}

variable "user_ocid" {
}


terraform {
    required_providers {
        oci = {
            source  = "oracle/oci"
	    #version = "4.87.0"
        }
    }
}

provider "oci" {
   auth = "InstancePrincipal"
   region = "${var.region}"
}

