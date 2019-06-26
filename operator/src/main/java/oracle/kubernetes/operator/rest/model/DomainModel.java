// Copyright 2017, 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.rest.model;

/** DomainModel describes a WebLogic domain that has been registered with the WebLogic operator. */
public class DomainModel extends ItemModel {

  /** Construct an empty DomainModel. */
  public DomainModel() {
  }

  /**
   * Construct a populated DomainModel.
   *
   * @param domainUid - the unique identifier assigned to the WebLogic domain that contains this
   *     cluster.
   */
  public DomainModel(String domainUid) {
    setDomainUid(domainUid);
  }

  private String domainUid;

  /**
   * Get the unique identifier that has been assigned to this WebLogic domain.
   *
   * @return the domain's unique identifier.
   */
  public String getDomainUid() {
    return domainUid;
  }

  /**
   * Set the unique identifier that has been assigned to this WebLogic domain.
   *
   * @param domainUid - the domain's unique identifier.
   */
  public void setDomainUid(String domainUid) {
    this.domainUid = domainUid;
  }

  @Override
  protected String propertiesToString() {
    return "domainUid=" + getDomainUid() + ", " + super.propertiesToString();
  }
}
