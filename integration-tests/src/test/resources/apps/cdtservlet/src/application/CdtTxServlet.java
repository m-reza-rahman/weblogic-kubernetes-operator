// Copyright (c) 2020, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package application;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Hashtable;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSContext;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.*;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.transaction.SystemException;
import javax.transaction.xa.Xid;

import weblogic.transaction.Transaction;
import weblogic.transaction.TransactionHelper;
import weblogic.transaction.TransactionManager;

import static java.lang.Thread.sleep;
     
@WebServlet(name="cdttxservlet", urlPatterns={"/cdttxservlet"},
     initParams={ @WebInitParam(name="simpleParam", value="paramValue") } )

public class CdtTxServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private TransactionManager tm = (TransactionManager)
      TransactionHelper.getTransactionHelper().getTransactionManager();
  PrintWriter out = null;
  String tableName = "cdt_table1";
  java.sql.Connection conn;

  /**
   * Handles the HTTP <code>GET</code> method.
   * Servlet send a message to a local queue and a remote (different domain) DB in distributed tx
   *
   * @param request  servlet request
   * @param response servlet response
   * @throws ServletException if a servlet-specific error occurs
   * @throws IOException      if an I/O error occurs
   */
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    out = response.getWriter();
    System.out.println("in cdttxservlet doGet method");

    String nsParam = request.getParameter("namespaces");
    if (nsParam == null) {
      return;
    }
    String[] namespace = nsParam.split(",");

    String domain1NsParam = namespace[0];
    String domain2NsParam = namespace[1];
    System.out.println("in cdttxservlet doGet method - domain1NsParam = " + domain1NsParam);
    System.out.println("in cdttxservlet doGet method - domain2NsParam = " + domain2NsParam);

    String domain2Url = "t3://domain2-managed-server1." + domain2NsParam + ","
        + "domain2-managed-server2." + domain2NsParam + ":8001";
    //String tableName = "cdt_table";
    //java.sql.Connection conn;
    Destination d;
    Context ctx;
    JMSContext context;
    String jmsMsg ="(D1) A Transcated JMS Message";

    try {
      DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
      java.util.Date date = new java.util.Date();
      System.out.println("Today Time [" + dateFormat.format(date) + "]");
      out.println("Today Time [" + dateFormat.format(date) + "]");
      out.println("");

      Hashtable h1 = new Hashtable();
      h1.put(Context.INITIAL_CONTEXT_FACTORY,
          "weblogic.jndi.WLInitialContextFactory");
      String providerUrl = "t3://domain1-managed-server1." + domain1NsParam + ","
          + "domain1-managed-server2." + domain1NsParam + ":8001";
      System.out.println("providerUrl = " + providerUrl);
      h1.put(Context.PROVIDER_URL, providerUrl);

      //Get the context from domain1. This is where JMS Queue is configured
      ctx = new InitialContext(h1);
      System.out.println("Got Local InitialContext [" + ctx + "]");
      out.println("Got Local InitialContext [" + ctx + "]");

      ConnectionFactory qcf = (ConnectionFactory) ctx.lookup("weblogic.jms.XAConnectionFactory");
      d = (Destination) ctx.lookup("jms/testCdtUniformQueue");
      System.out.println("Got Local JMS Destination " + d);
      out.println("Got Local JMS Destination " + d);
      context = qcf.createContext();

      //Get context from domain2. This is where JDBC DS is configured
      out.println("Getting Remote Context from " + domain2Url);
      Hashtable h = new Hashtable();
      h.put(Context.INITIAL_CONTEXT_FACTORY,
          "weblogic.jndi.WLInitialContextFactory");
      h.put(Context.PROVIDER_URL, domain2Url);

      Context ctx2 = new InitialContext(h);
      System.out.println("Got Remote InitialContext [" + ctx2 + "]");
      out.println("Got Remote InitialContext [" + ctx2 + "]");

      javax.sql.DataSource ds = (javax.sql.DataSource) ctx2.lookup("jdbc/TestCdtDataSource");
      System.out.println("Got ds from context - " + ds);
      out.println("Got ds from context - " + ds);
      conn = ds.getConnection();
      System.out.println("Got connection to datasource - " + conn);
      out.println("Got connection to datasource - " + conn);

      //create a table in the DB
      //createTable(conn, tableName);

      try {
        tm.begin();
        Transaction tx = (Transaction) tm.getTransaction();
        Xid xid = tx.getXID();
        System.out.println("xid=" + xid);
        tx.setProperty("TMAfterTLogBeforeCommitExit", Boolean.TRUE);

        Connection conn1 = ds.getConnection();
        System.out.println("Got connection to datasource - " + conn1);
        out.println("Got connection to datasource - " + conn1);
        insertData(conn1, tableName);
        System.out.println("CdTXServlet: Inserted data");
        out.println("CdTXServlet: Inserted data");
        context.createProducer().send(d, jmsMsg);
        System.out.println("CdTXServlet: sent message to jms queue");
        out.println("CdTXServlet: sent message to jms queue");

        System.out.println("tx.toString() = " + tx.toString());
        out.println("tx.toString() = " + tx.toString());

        tm.commit();
      } catch (SystemException se) {
        if (!(se.toString().contains("weblogic.rjvm.PeerGoneException"))) {
          throw se;
        }
        System.out.println("CdTXServlet: Got PeerGoneException " + se);
        out.println("CdTXServlet:Exception got - " + se.toString());
        out.println("CdTXServlet: got PeerGoneException as expected");
      }

      out.println("Message Delivered in Distributed Transcation");

      sleep(30);

      String body = context.createConsumer(d).receiveBody(String.class);
      System.out.println(getDateAndTimeStamp() + ":   message received from the queue : " + body);
      out.println("message received from the queue : " + body);
      boolean msgRecd = jmsMsg.equals(body);

      boolean dataGotInserted = readData(conn, tableName);
      if (dataGotInserted && msgRecd) {
        System.out.println(getDateAndTimeStamp() + ":   Status=SUCCESS: Transaction committed successfully with TMAfterTLogBeforeCommitExit set");
        out.println(getDateAndTimeStamp() + ":  Status=SUCCESS: Transaction committed successfully with TMAfterTLogBeforeCommitExit set");
      } else {
        out.println("Status=FAILURE: Transaction failed to commit with TMAfterTLogBeforeCommitExit set");
      }

    } catch (Exception unk) {
      out.println("Got Exception when creating table or inserting data into db table or JMS queue " + unk);
      unk.printStackTrace();
    } finally {
      try {
        dropTable(conn, tableName);
        conn.close();
      } catch (SQLException sqle) {
        sqle.printStackTrace();
      }
    }
    out.close();
  }

  public static String getDateAndTimeStamp() {
    DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    Date date = new Date();
    return dateFormat.format(date) + "-" + System.currentTimeMillis();
  }

  protected void doPost(HttpServletRequest request,
                        HttpServletResponse response)
      throws ServletException, IOException {
    doGet(request, response);
  }

  private void createTable(Connection conn, String tableName) throws SQLException {
    Statement stmt = null;

    /*
    try {
      //String selectSQL = String.format("desc '%s'; ROLLBACK; commit; UNLOCK TABLES;", tableName);
      String selectSQL = String.format("desc '%s'; UNLOCK TABLES;", tableName);
      System.out.println("selectSQL 0=== " + selectSQL);
      out.println("=== selectSQL 0= " + selectSQL);
      stmt = conn.createStatement();
      boolean checkDone = stmt.execute(selectSQL);
      System.out.println("Done checking table exist 0 - closing stmt: " + checkDone);
      out.println("=== Done checking table exist 0 - closing stmt: " + checkDone);
      stmt.close();

      selectSQL = String.format("SELECT object_id FROM dba_objects WHERE object_name='%s'", tableName);;
      System.out.println("selectSQL 1= " + selectSQL);
      out.println("=== selectSQL 1= " + selectSQL);
      stmt = conn.createStatement();
      boolean checkDone1 = stmt.execute(selectSQL);
      System.out.println("Done checking table exist 1 - closing stmt: " + checkDone1);
      out.println("=== Done checking table exist 1 - closing stmt: " + checkDone1);
      stmt.close();

      selectSQL = String.format("select * from %s where rownum=1", tableName);;
      System.out.println("selectSQL 2= " + selectSQL);
      out.println("selectSQL 2= " + selectSQL);
      stmt = conn.createStatement();
      boolean checkDone2 = stmt.execute(selectSQL);
      System.out.println("Done checking table exist 2 - closing stmt: " + checkDone2);
      out.println("Done checking table exist 2 - closing stmt: " + checkDone2);
      stmt.close();

      System.out.println("===deleting table before create if exists");
      out.println("===deleting table before create if exists");
      dropTable(conn, tableName);
      System.out.println("===Done deleting table before create if exists");
      out.println("===Done deleting table before create if exists");
    } catch (SQLException sqle) {
      System.out.println("Got SQL Exception when checking table exist from table ");
      sqle.getMessage();
    } finally {
      out.println("Done checking table exist - closing stmt");
      stmt.close();
    }*/

    try {
    /*
      int id = 1;
      String data = "yay! this got in the db table";
      String deleteSQL = String.format("delete from %s where test_id = '%d'", tableName, id);
      System.out.println("deleteSQL = " + deleteSQL);
      stmt = conn.createStatement();
      stmt.execute(deleteSQL);
      out.println("Done deleting a row - closing stmt");
      stmt.close();*/

      System.out.println("===deleting table before create if exists");
      out.println("===deleting table before create if exists");
      String dropSQL = String.format("drop table %s purge", tableName);
      System.out.println("===deleting table String = " + dropSQL);
      out.println("===deleting table String = " + dropSQL);
      stmt = conn.createStatement();
      stmt.execute(dropSQL);
      System.out.println("===Done deleting table before create if exists");
      out.println("===Done deleting table before create if exists");
    } catch (SQLException sqle) {
      System.out.println("===Got SQL Exception when deleting table " + sqle.getMessage());
      out.println("===Got SQL Exception when deleting table " + sqle.getMessage());
    } finally {
      System.out.println("===Done deleting table - closing stmt");
      out.println("===Done deleting table - closing stmt");
      stmt.close();
    }

    try {
      String createSQL = String.format("create table %s (test_id int, test_data varchar(120))", tableName);
      System.out.println("create table String = " + createSQL);
      out.println("create table String = " + createSQL);
      stmt = conn.createStatement();
      stmt.execute(createSQL);
    } catch (SQLException sqle) {
      System.out.println("Got SQL Exception when creating table ==> " + sqle.getMessage().toString());
      out.println("Got SQL Exception when creating table ");
      sqle.getMessage();
      throw sqle;
    } finally {
      out.println("Created table - closing stmt");
      stmt.close();
    }
  }

  private void dropTable(Connection conn, String tableName) throws SQLException {
    Statement stmt = null;
    try {
      String dropSQL = String.format("drop table %s purge", tableName);
      System.out.println("drop table String = " + dropSQL);
      out.println("drop table String = " + dropSQL);
      stmt = conn.createStatement();
      stmt.execute(dropSQL);
    } catch (SQLException sqle) {
      System.out.println("Got SQL Exception when dropping table ");
      out.println("Got SQL Exception when dropping table ");
      sqle.getMessage();
      throw sqle;
    } finally {
      out.println("Drop table - closing stmt");
      stmt.close();
    }
  }
  private void insertData(Connection conn, String tableName) throws SQLException {
    Statement stmt = null;

    try {
      int id = 1;
      String data = "yay! this got in the db table";
      String insertSQL = String.format("insert into %s values ('%d','%s')", tableName, id, data);
      System.out.println("insertSQL = " + insertSQL);
      out.println("insertSQL = " + insertSQL);
      stmt = conn.createStatement();
      System.out.println("Inserted Statement");
      out.println("Inserted Statement");
      stmt.execute(insertSQL);
    } catch (SQLException sqle) {
      System.out.println("Got SQL Exception when inserting into table ");
      out.println("Got SQL Exception when inserting into table ");
      sqle.getMessage();
      throw sqle;
    } finally {
      out.println("Done inserting - closing stmt");
      stmt.close();
    }
  }

  private boolean readData(Connection conn, String tableName) throws SQLException {
    boolean getData = false;
    Statement stmt = null;
    try {
      String data = "yay! this got in the db table";
      String selectSQL = String.format("select * from %s", tableName);
      out.println("selectSQL = " + selectSQL);
      stmt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                                   ResultSet.CONCUR_UPDATABLE);
      out.println("Created Statement");
      ResultSet rs = stmt.executeQuery(selectSQL);
      rs.first();
      System.out.println("got from DB - " + rs.getInt(1));
      out.println("got from DB - " + rs.getInt(1));
      String message = rs.getString(2);
      System.out.println(getDateAndTimeStamp() + ":   got from DB - " + message);
      out.println("got from DB - " + message);
      getData = data.equals(message);
    } catch (SQLException sqle) {
      System.out.println("Got SQL Exception when reading from table ");
      out.println("Got SQL Exception when reading from table ");
      sqle.getMessage();
      throw sqle;
    } finally {
      stmt.close();
    }
    return getData;
  }

}
