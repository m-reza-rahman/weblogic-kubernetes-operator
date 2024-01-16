// Copyright (c) 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.model;

import oracle.kubernetes.json.Description;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

public class DomainConditionFailureInfo {

  @Description("The introspectVersion set when the Failed condition occurred.")
  private String introspectVersion;

  @Description("The restartVersion set when the Failed condition occurred.")
  private String restartVersion;

  @Description("The image used by the introspector when the Failed condition occurred.")
  private String introspectImage;

  public DomainConditionFailureInfo() {
  }

  public DomainConditionFailureInfo(DomainConditionFailureInfo other) {
    this.introspectVersion = other.introspectVersion;
    this.restartVersion = other.restartVersion;
    this.introspectImage = other.introspectImage;
  }

  public String getIntrospectVersion() {
    return introspectVersion;
  }

  public void setIntrospectVersion(String introspectVersion) {
    this.introspectVersion = introspectVersion;
  }

  public DomainConditionFailureInfo withIntrospectVersion(String introspectVersion) {
    setIntrospectVersion(introspectVersion);
    return this;
  }

  public String getRestartVersion() {
    return restartVersion;
  }

  public void setRestartVersion(String restartVersion) {
    this.restartVersion = restartVersion;
  }

  public DomainConditionFailureInfo withRestartVersion(String restartVersion) {
    setRestartVersion(restartVersion);
    return this;
  }

  public String getIntrospectImage() {
    return introspectImage;
  }

  public void setIntrospectImage(String introspectImage) {
    this.introspectImage = introspectImage;
  }

  public DomainConditionFailureInfo withIntrospectImage(String introspectImage) {
    setIntrospectImage(introspectImage);
    return this;
  }

  @Override
  public String toString() {
    return new ToStringBuilder(this)
        .appendSuper(super.toString())
        .append("introspectVersion", introspectVersion)
        .append("restartVersion", restartVersion)
        .append("introspectImage", introspectImage)
        .toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    DomainConditionFailureInfo other = (DomainConditionFailureInfo) o;

    return new EqualsBuilder()
        .appendSuper(super.equals(o))
        .append(introspectVersion, other.introspectVersion)
        .append(restartVersion, other.restartVersion)
        .append(introspectImage, other.introspectImage)
        .isEquals();
  }

  @Override
  public int hashCode() {
    return new HashCodeBuilder(17, 37)
        .appendSuper(super.hashCode())
        .append(introspectVersion)
        .append(restartVersion)
        .append(introspectImage)
        .toHashCode();
  }
}
