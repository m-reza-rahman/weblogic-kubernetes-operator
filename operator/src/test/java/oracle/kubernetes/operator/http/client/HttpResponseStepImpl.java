// Copyright (c) 2020, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.http.client;

import java.net.http.HttpResponse;

import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

public class HttpResponseStepImpl extends HttpResponseStep {
  private HttpResponse<String> successResponse;
  private HttpResponse<String> failureResponse;

  public HttpResponseStepImpl(Step step) {
    super(step);
  }

  HttpResponse<String> getSuccessResponse() {
    return successResponse;
  }

  HttpResponse<String> getFailureResponse() {
    return failureResponse;
  }

  @Override
  public Void onSuccess(Packet packet, HttpResponse<String> response) {
    successResponse = response;
    return doNext(packet);
  }

  @Override
  public Void onFailure(Packet packet, HttpResponse<String> response) {
    failureResponse = response;
    return doNext(packet);
  }
}
