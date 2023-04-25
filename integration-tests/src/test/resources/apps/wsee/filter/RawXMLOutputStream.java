// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package saml.sendervouches.filter;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;

public class RawXMLOutputStream extends RawXMLOutputStreamBase {

  public RawXMLOutputStream(HttpServletResponse response) throws IOException {
    super(response);
    closed = false;
    commit = false;
    count = 0;
    this.response = response;
    this.output = response.getOutputStream();
    this.baos = new ByteArrayOutputStream();
  }
}
