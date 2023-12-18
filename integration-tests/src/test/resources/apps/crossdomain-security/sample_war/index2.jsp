<!--
Copyright (c) 2023, Oracle and/or its affiliates.
Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
-->
<%@ page import="java.net.UnknownHostException" %>
<%@ page import="java.net.InetAddress" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@page contentType="text/html" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
  <head>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
    <c:url value="/res/styles.css" var="stylesURL"/>
    <link rel="stylesheet" href="${stylesURL}" type="text/css"> 
    <title>Test WebApp</title>
  </head>
  <body>
    <%
      String hostname, serverAddress;
      hostname = "error";
      serverAddress = "error";
      try {
        InetAddress inetAddress;
        inetAddress = InetAddress.getLocalHost();
        hostname = inetAddress.getHostName();
        serverAddress = inetAddress.toString();
      } catch (UnknownHostException e) {

        e.printStackTrace();
      }
    %>

    <li>InetAddress: <%=serverAddress %>
    <li>InetAddress.hostname: <%=hostname %>

  </body>
</html>
