// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package saml.sendervouches.filter;

import java.io.*;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import weblogic.xml.saaj.SOAPConstants;

public class RawXMLInputStream extends RawXMLInputStreamBase {
  private static int count = 0;
  protected String inputStr = null;
  protected String samlAssertionReplace = null;

  public RawXMLInputStream(HttpServletRequest req) {
    super(req);
    this.request = req;
    try {
      this.input = req.getInputStream();

      StringBuffer sbuf = new StringBuffer();
      int i = input.read();
      while (i != -1) {
        sbuf.append((char)i);
        i = input.read();
      }
      inputStr = sbuf.toString().trim();

      if(inputStr.indexOf("echoSignedSamlV11Token11ExpiredAssertion") != (-1)) {
          trace("Modify assertion to expire");
          String conditions ="NotOnOrAfter=";
          int k = inputStr.indexOf(conditions);
          String year = inputStr.substring(k + 14, k + 18);
          trace("Found year: " + year);
          inputStr = inputStr.replace(conditions + '"' + year, conditions + '"' +"2004");
      }
      if(inputStr.indexOf("echoSignedSamlV11Token11ModifiedSubjectName") != (-1)) {
        trace("Modify assertion to have wrong user");
        String conditions ="user_d1";
        inputStr = inputStr.replace(conditions, "system");
        trace( "Modified \n" + inputStr);
      }
      if(inputStr.indexOf("echoUnsignedSamlV11Token10") != (-1)) {
        trace("Save assertion in memory");
		int start = 0;
		int end = 0;
		start = inputStr.indexOf("<Assertion", start);
		if (start == -1) {
		  throw new Exception("Did not find start tag for Assertion ");
		  }
		  end = inputStr.indexOf("</Assertion>", start);
		  if (end == -1) {
		    throw new Exception("Did not find end tag for Assertion" );
			}
			samlAssertionReplace = inputStr.substring(start, end + "</Assertion>".length());
			try {
	        BufferedWriter out = new BufferedWriter(new FileWriter("samlAssertionReplaceFile"));
	        out.write(samlAssertionReplace);
	        out.close();
		    } catch (IOException e) {
		    }

		  trace(" assertion" + samlAssertionReplace);
      }

      // trace("InputStream read:\n" + inputStr);

      String soapEnvelope = SOAPConstants.ENV_PREFIX + ":Envelope";
      if (inputStr.indexOf(soapEnvelope) == -1) {
        isRawXML = true;
      }

      /*
      if (isRawXML) {
        inputStr = top + inputStr + bottom;
        trace("Modified stream data:\n" + inputStr);
      }
      */

      this.bais = new ByteArrayInputStream(inputStr.getBytes());
      count++;
    } catch(Exception e) {
      e.printStackTrace();
    }
  }
  public String getInputStr() {
      return this.inputStr;
	    }

}


