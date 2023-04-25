// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package saml.sendervouches.filter;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;

public class RawXMLResponseWrapper extends HttpServletResponseWrapper {
  private CharArrayWriter writer;
  private RawXMLOutputStream outStream;

  public String toString() {
    return outStream.toString();
  }

  public RawXMLResponseWrapper(HttpServletResponse response) {
    super(response);
    try {
      writer = new CharArrayWriter();
      outStream = new RawXMLOutputStream(response);
	  } catch(Exception e) {
	    e.printStackTrace();
	  }
  }

  public ServletOutputStream getOutputStream() {
    return outStream;
  }

  public PrintWriter getWriter() {
    return new PrintWriter(writer);
  }

  public ServletOutputStream getCopiedStream() throws IOException {
    return outStream.getCopiedStream();
  }

}


