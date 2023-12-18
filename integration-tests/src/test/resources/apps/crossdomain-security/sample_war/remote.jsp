<!--
Copyright (c) 2023, Oracle and/or its affiliates.
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
  Context ctx = null;

  String remoteurl = request.getParameter("remoteurl");
  out.println("Remote URL is [" + remoteurl + "]");

  String action = request.getParameter("action"); 
  out.println("Transcation action [" + action + "]");

  Hashtable env = new Hashtable();
  env.put(Context.INITIAL_CONTEXT_FACTORY,
          "weblogic.jndi.WLInitialContextFactory");
  env.put(Context.PROVIDER_URL, remoteurl);
  // Remote anonymous RMI access via T3 not allowed
  env.put(Context.SECURITY_PRINCIPAL, "weblogic");
  env.put(Context.SECURITY_CREDENTIALS, "welcome1");
  ctx = new InitialContext(env);
  out.println("Got Remote Context successfully");

  // lookup JMS XAConnectionFactory
  ConnectionFactory qcf=
       (ConnectionFactory)ctx.lookup("weblogic.jms.XAConnectionFactory");
  out.println("JMS ConnectionFactory lookup Successful ...");

  TransactionHelper tranhelp =TransactionHelper.getTransactionHelper();
  UserTransaction ut = tranhelp.getUserTransaction();

  JMSContext context = qcf.createContext();
  out.println("JMS Context Created Successfully ...");
  Destination queue = (Destination)ctx.lookup("jms.admin.adminQueue");
  out.println("JMS Destination lookup Successful ...");

  ut.begin();
  context.createProducer().send(queue, "Message to a Destination");
  out.println(ut);

  if ( action.equals("commit") ) {
    ut.commit();
    out.println("Message sent in a commit User Transation ...");
  } else {
    ut.rollback();
    out.println("Message sent in a rolled-back User Transation ...");
  }
  out.println("Sent a Text message to Destination in a User Transation ...");
} catch(Exception e) {
   out.println("Got an Exception [" + e + "]");
}
%>
