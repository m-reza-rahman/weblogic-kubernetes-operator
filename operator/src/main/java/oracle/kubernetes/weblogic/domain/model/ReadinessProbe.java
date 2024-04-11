// Copyright (c) 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.model;

import oracle.kubernetes.json.Description;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

public class ReadinessProbe extends ProbeTuning {
  @Description("Path to access for the readiness probe. Defaults to /weblogic/ready")
  private String httpGetActionPath = null;

  void copyValues(ReadinessProbe fromProbe) {
    super.copyValues(fromProbe);
    if (httpGetActionPath == null) {
      httpGetActionPath(fromProbe.httpGetActionPath);
    }
  }

  public String getHttpGetActionPath() {
    return httpGetActionPath;
  }

  public ReadinessProbe httpGetActionPath(String httpGetActionPath) {
    this.httpGetActionPath = httpGetActionPath;
    return this;
  }

  @Override
  public String toString() {
    return new ToStringBuilder(this)
          .appendSuper(super.toString())
          .append("httpGetActionPath", httpGetActionPath)
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

    ReadinessProbe that = (ReadinessProbe) o;

    return new EqualsBuilder()
          .appendSuper(super.equals(o))
          .append(httpGetActionPath, that.httpGetActionPath)
          .isEquals();
  }

  @Override
  public int hashCode() {
    return new HashCodeBuilder(17, 37)
          .appendSuper(super.hashCode())
          .append(httpGetActionPath)
          .toHashCode();
  }

}
