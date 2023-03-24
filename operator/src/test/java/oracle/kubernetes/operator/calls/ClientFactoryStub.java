// Copyright (c) 2018, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.meterware.simplestub.Memento;
import com.meterware.simplestub.StaticStubSupport;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;

public class ClientFactoryStub implements ClientFactory {
  private final WireMockRule rule;

  private ClientFactoryStub(WireMockRule rule) {
    this.rule = rule;
  }

  public static Memento install(WireMockRule rule) throws NoSuchFieldException {
    return StaticStubSupport.install(Client.class, "factory", new ClientFactoryStub(rule));
  }

  @Override
  public ApiClient get() {
    return new ClientBuilder().setBasePath("http://localhost:" + rule.port()).build();
  }
}
