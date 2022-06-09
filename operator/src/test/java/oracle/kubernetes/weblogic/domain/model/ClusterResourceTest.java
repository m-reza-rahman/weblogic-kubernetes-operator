// Copyright (c) 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.model;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class ClusterResourceTest {

  private final ClusterResource resource = new ClusterResource().spec(new Cluster());

  @Test
  void whenResourceInitialized_hasCorrectApiVersionAndKind() {
    assertThat(resource.getApiVersion(), equalTo("weblogic.oracle/v1"));
    assertThat(resource.getKind(), equalTo("Cluster"));
  }

  @Test
  void canReadReplicaCount() {
    resource.spec(new Cluster().withReplicas(2));

    assertThat(resource.getReplicas(), equalTo(2));
  }

  @Test
  void canSetReplicaCount() {
    resource.setReplicas(5);

    assertThat(resource.getSpec().getReplicas(), equalTo(5));
  }

  @Test
  void canReadClusterNameFromSpec() {
    resource.spec(new Cluster().withClusterName("cluster-1"));

    assertThat(resource.getClusterName(), equalTo("cluster-1"));
  }

  @Test
  void canReadClusterNameFromMetadata() {
    resource.setMetadata(new V1ObjectMeta().name("cluster-2"));

    assertThat(resource.getClusterName(), equalTo("cluster-2"));
  }

  @Test
  void whenNameInBothMetadataAndSpec_useNameFromSpec() {
    resource.withMetadata(new V1ObjectMeta().name("cluster-2")).spec(new Cluster().withClusterName("cluster-1"));

    assertThat(resource.getClusterName(), equalTo("cluster-1"));
  }

  @Test
  void canReadDomainUidFromSpec() {
    resource.spec(new Cluster().withDomainUid("domain1"));

    assertThat(resource.getDomainUid(), equalTo("domain1"));
  }

  @Test
  void canReadDomainUidFromMetadata() {
    resource.setMetadata(new V1ObjectMeta().putLabelsItem("weblogic.domainUID",
            "domain2"));

    assertThat(resource.getDomainUid(), equalTo("domain2"));
  }

  @Test
  void whenDomainUidInBothMetadataAndSpec_useNameFromSpec() {
    resource.withMetadata(new V1ObjectMeta().putLabelsItem("weblogic.domainUID",
            "domain1")).spec(new Cluster().withDomainUid("domain3"));

    assertThat(resource.getDomainUid(), equalTo("domain3"));
  }
}
