// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.model;

import java.util.List;

import oracle.kubernetes.json.Default;
import oracle.kubernetes.json.Description;
import oracle.kubernetes.operator.DomainType;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

public class Domain {

  @Description("Domain creation mode. Legal values: CreateDomainIfNotExists, CreateDomainWithRcuIfNotExists."
      + " Defaults to CreateDomainIfNotExists.")
  @Default(strDefault = "CreateDomainIfNotExists")
  private CreateIfNotExists createIfNotExists = CreateIfNotExists.DOMAIN;

  @Description("WebLogic Deploy Tooling domain type. Legal values: WLS, JRF. Defaults to JRF.")
  @Default(strDefault = "JRF")
  private DomainType domainType = DomainType.JRF;

  /**
   * The domain images.
   *
   */
  @Description("Domain images containing WebLogic Deploy Tooling model, application archive, and WebLogic Deploy "
      + "Tooling installation files."
      + " These files will be used to create the domain during introspection. This feature"
      + " internally uses a Kubernetes emptyDir volume and Kubernetes init containers to share"
      + " the files from the additional images ")
  private List<DomainCreationImage> domainCreationImages;

  @Description("Name of a ConfigMap containing the WebLogic Deploy Tooling model.")
  private String wdtConfigMap;

  @Description("Settings for OPSS security.")
  private Opss opss;

  public CreateIfNotExists getCreateIfNotExists() {
    return createIfNotExists;
  }

  public Domain createMode(CreateIfNotExists createIfNotExists) {
    this.createIfNotExists = createIfNotExists;
    return this;
  }

  public DomainType getDomainType() {
    return domainType;
  }

  public Domain domainType(DomainType domainType) {
    this.domainType = domainType;
    return this;
  }

  public List<DomainCreationImage> getDomainCreationImages() {
    return domainCreationImages;
  }

  public Domain wdtImages(List<DomainCreationImage> wdtImages) {
    this.domainCreationImages = wdtImages;
    return this;
  }

  public String getWdtConfigMap() {
    return wdtConfigMap;
  }

  public Domain wdtConfigMap(String wdtConfigMap) {
    this.wdtConfigMap = wdtConfigMap;
    return this;
  }

  public Opss getOpss() {
    return opss;
  }

  public Domain opss(Opss opss) {
    this.opss = opss;
    return this;
  }

  @Override
  public String toString() {
    ToStringBuilder builder =
        new ToStringBuilder(this)
            .append("createMode", createIfNotExists)
            .append("domainType", domainType)
            .append("wdtImages", domainCreationImages)
            .append("wdtConfigMap", wdtConfigMap)
            .append("opss", opss);

    return builder.toString();
  }

  @Override
  public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder()
        .append(createIfNotExists)
        .append(domainType)
        .append(domainCreationImages)
        .append(wdtConfigMap)
        .append(opss);

    return builder.toHashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    } else if (!(other instanceof Domain)) {
      return false;
    }

    Domain rhs = ((Domain) other);
    EqualsBuilder builder =
        new EqualsBuilder()
            .append(createIfNotExists, rhs.createIfNotExists)
            .append(opss, rhs.opss)
            .append(domainType, rhs.domainType)
            .append(wdtConfigMap, rhs.wdtConfigMap)
            .append(opss, rhs.opss);

    return builder.isEquals();
  }
}
