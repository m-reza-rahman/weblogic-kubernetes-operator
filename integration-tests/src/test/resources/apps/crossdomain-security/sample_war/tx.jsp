<!--
Copyright (c) 2024, Oracle and/or its affiliates.
Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
-->
<%@ page import="java.io.IOException" %>
<%@ page import="java.util.Hashtable" %>
<%@ page import="javax.naming.Context" %>
<%@ page import="javax.naming.InitialContext" %>
<%@ page import="javax.naming.NamingException" %>
<%@ page import="javax.servlet.ServletException" %>
<%@ page import="javax.servlet.annotation.WebServlet" %>
<%@ page import="javax.servlet.http.HttpServlet" %>
<%@ page import="javax.servlet.http.HttpServletRequest" %>
<%@ page import="javax.servlet.http.HttpServletResponse" %>
<%@ page import="javax.servlet.http.HttpServletResponse" %>
<%@ page import="javax.jms.Destination" %>
<%@ page import="javax.jms.ConnectionFactory" %>
<%@ page import="javax.jms.JMSContext" %>
<%@ page import="javax.jms.Message" %>
<%@ page import="javax.jms.JMSConsumer" %>
<%@ page import="javax.jms.QueueBrowser" %>
<%@ page import="weblogic.transaction.TransactionHelper" %>
<%@ page import="weblogic.transaction.TransactionManager" %>
<%@ page import="javax.transaction.UserTransaction" %>

<%
try {
  Context lctx = null;
  Context rctx = null;

  String remoteurl = request.getParameter("remoteurl");
  out.println("Remote URL is");
  out.println(remoteurl);

  String action = request.getParameter("action"); 
  out.println("Transcation action -->");
  out.println(request.getParameter("action"));

  lctx = new InitialContext();
  out.println("(Local) Got Context successfully");
  TransactionHelper tranhelp =TransactionHelper.getTransactionHelper();
  UserTransaction ut = tranhelp.getUserTransaction();

  ConnectionFactory qcf=
       (ConnectionFactory)lctx.lookup("weblogic.jms.XAConnectionFactory");
  out.println("(Local) JMS ConnectionFactory lookup Successful ...");
  JMSContext context = qcf.createContext();
  out.println("(Local) JMS Context Created Successfully ...");
  Destination queue = (Destination)lctx.lookup("jms.admin.adminQueue");
  out.println("(Local) Destination (jms.admin.adminQueue) lookup Successful");

  ut.begin();

  // Send message to local Destination
  context.createProducer().send(queue, "Message to a Local Destination");
  lctx.close();

  Hashtable env = new Hashtable();
  env.put(Context.INITIAL_CONTEXT_FACTORY,
          "weblogic.jndi.WLInitialContextFactory");
  env.put(Context.PROVIDER_URL, remoteurl);
  // Remote anonymous RMI access via T3 not allowed
  env.put(Context.SECURITY_PRINCIPAL, "weblogic");
  env.put(Context.SECURITY_CREDENTIALS, "welcome1");
  rctx = new InitialContext(env);
  out.println("(Remote) Got Context successfully");

  // lookup JMS XAConnectionFactory
  ConnectionFactory qcf2=
       (ConnectionFactory)rctx.lookup("weblogic.jms.XAConnectionFactory");
  out.println("(Remote) JMS ConnectionFactory lookup Successful ...");

  JMSContext context2 = qcf2.createContext();
  out.println("(Remote) JMS Context Created Successfully ...");
  Destination queue2 = (Destination)rctx.lookup("jms.admin.adminQueue");
  out.println("(Remote) Destination (jms.admin.adminQueue) lookup Successful");
  context2.createProducer().send(queue2, "Message to a Remote Destination");
  rctx.close();

  out.println(ut);

  // Get the live context from Tx Coordinator before closing transaction 
  // Context ctx = new InitialContext(env);

  if ( action.equals("commit") ) {
    ut.commit();
    out.println("Message sent in a commit User Transation ...");
  } else {
    ut.rollback();
    out.println("Message sent in a rolled-back User Transation ...");
  }
  out.println("Sent a Text message to Destination in a User Transation ...");
} catch(Exception e) {
   out.println(e);
   out.println("Got an Exception");
}
%>
