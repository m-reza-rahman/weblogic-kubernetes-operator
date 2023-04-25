// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package saml.sendervouches.filter;

import java.io.*;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;

public class RawXMLRequestWrapper extends HttpServletRequestWrapper {
	private RawXMLInputStream rxis = null;

	public RawXMLRequestWrapper(HttpServletRequest req) {
		super(req);
		rxis = new RawXMLInputStream(req);
	}

	public ServletInputStream getInputStream() {
		return rxis;
	}

	public String getHeader(String name) {
		if (name.equalsIgnoreCase("Content-Type")) {
			return "text/xml";
		} else {
			return super.getHeader(name);
		}
	}

	public boolean isRawXML() {
		return rxis.isRawXML();
	}

	public String toString() {
		return rxis.getInputStr();
	}

}


