// Copyright (c) 2019, 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import com.google.gson.annotations.SerializedName;
import oracle.kubernetes.common.Labeled;

public enum ModelInImageDomainType implements Labeled {
  WLS("WLS"),
  @SerializedName("RestrictedJRF")
  RESTRICTED_JRF("RestrictedJRF"),
  JRF("JRF");

  private final String label;

  ModelInImageDomainType(String label) {
    this.label = label;
  }

  @Override
  public String label() {
    return label;
  }

  @Override
  public String toString() {
    return label();
  }
}
