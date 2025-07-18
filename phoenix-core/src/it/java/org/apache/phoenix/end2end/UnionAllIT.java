/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.apache.phoenix.compile.ExplainPlan;
import org.apache.phoenix.compile.ExplainPlanAttributes;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixPreparedStatement;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.TestUtil;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(ParallelStatsDisabledTest.class)
public class UnionAllIT extends ParallelStatsDisabledIT {

  @Test
  public void testUnionAllSelects() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl =
        "CREATE TABLE " + tableName1 + " " + "  (a_string varchar(10) not null, col1 integer"
          + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 10);
      stmt.execute();
      conn.commit();

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string char(20) not null, col1 bigint"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);
      dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
      stmt = conn.prepareStatement(dml);
      stmt.setString(1, "b");
      stmt.setInt(2, 20);
      stmt.execute();
      stmt.setString(1, "c");
      stmt.setInt(2, 20);
      stmt.execute();
      conn.commit();

      ddl = "select * from " + tableName1 + " union all select * from " + tableName2
        + " union all select * from " + tableName1;
      ResultSet rs = conn.createStatement().executeQuery(ddl);
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("b", rs.getString(1).trim());
      assertEquals(20, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("c", rs.getString(1).trim());
      assertEquals(20, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testAggregate() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string char(5) not null, col1 tinyint"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 10);
      stmt.execute();
      stmt.setString(1, "d");
      stmt.setInt(2, 40);
      stmt.execute();
      stmt.setString(1, "e");
      stmt.setInt(2, 50);
      stmt.execute();
      conn.commit();

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);
      dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
      stmt = conn.prepareStatement(dml);
      stmt.setString(1, "b");
      stmt.setInt(2, 20);
      stmt.execute();
      stmt.setString(1, "c");
      stmt.setInt(2, 30);
      stmt.execute();
      conn.commit();

      String aggregate = "select count(*) from " + tableName1 + " union all select count(*) from "
        + tableName2 + " union all select count(*) from " + tableName1;
      ResultSet rs = conn.createStatement().executeQuery(aggregate);
      assertTrue(rs.next());
      assertEquals(3, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(3, rs.getInt(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testGroupBy() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 10);
      stmt.execute();
      conn.commit();

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);
      dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
      stmt = conn.prepareStatement(dml);
      stmt.setString(1, "b");
      stmt.setInt(2, 20);
      stmt.execute();
      stmt.setString(1, "c");
      stmt.setInt(2, 30);
      stmt.execute();
      conn.commit();

      String aggregate = "select count(*), col1 from " + tableName1
        + " group by col1 union all select count(*), col1 from " + tableName2 + " group by col1";
      ResultSet rs = conn.createStatement().executeQuery(aggregate);
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testOrderByLimit() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 10);
      stmt.execute();
      stmt.setString(1, "f");
      stmt.setInt(2, 10);
      stmt.execute();
      conn.commit();

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);
      dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
      stmt = conn.prepareStatement(dml);
      stmt.setString(1, "b");
      stmt.setInt(2, 20);
      stmt.execute();
      stmt.setString(1, "c");
      stmt.setInt(2, 30);
      stmt.execute();
      stmt.setString(1, "d");
      stmt.setInt(2, 30);
      stmt.execute();
      stmt.setString(1, "e");
      stmt.setInt(2, 30);
      stmt.execute();
      conn.commit();

      String aggregate = "select count(*), col1 from " + tableName2
        + " group by col1 union all select count(*), col1 from " + tableName1
        + " group by col1 order by col1";
      ResultSet rs = conn.createStatement().executeQuery(aggregate);
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(3, rs.getInt(1));
      assertFalse(rs.next());

      String limit = "select count(*), col1 x from " + tableName1
        + " group by col1 union all select count(*), col1 x from " + tableName2
        + " group by col1 order by x limit 2";
      rs = conn.createStatement().executeQuery(limit);
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertFalse(rs.next());

      String limitOnly =
        "select * from " + tableName1 + " union all select * from " + tableName2 + " limit 2";
      rs = conn.createStatement().executeQuery(limitOnly);
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("f", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSelectDiff() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      ddl = "select a_string, col1, col1 from " + tableName1 + " union all select * from "
        + tableName2 + " union all select a_string, col1 from " + tableName1;
      conn.createStatement().executeQuery(ddl);
      fail();
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.SELECT_COLUMN_NUM_IN_UNIONALL_DIFFS.getErrorCode(),
        e.getErrorCode());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testJoinInUnionAll() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 10);
      stmt.execute();
      conn.commit();

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
      stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 20);
      stmt.execute();
      conn.commit();

      ddl = "select x.a_string, y.col1  from " + tableName1 + " x, " + tableName2
        + " y where x.a_string=y.a_string union all " + "select t.a_string, s.col1 from "
        + tableName1 + " s, " + tableName2 + " t where s.a_string=t.a_string";
      ResultSet rs = conn.createStatement().executeQuery(ddl);
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(20, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertFalse(rs.next());

      ddl = "select x.a_string, y.col1  from " + tableName1 + " x join " + tableName2
        + " y on x.a_string=y.a_string union all " + "select t.a_string, s.col1 from " + tableName1
        + " s inner join " + tableName2 + " t on s.a_string=t.a_string";
      rs = conn.createStatement().executeQuery(ddl);
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(20, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertFalse(rs.next());

      ddl = "select x.a_string, y.col1  from " + tableName1 + " x left join " + tableName2
        + " y on x.a_string=y.a_string union all " + "select t.a_string, s.col1 from " + tableName1
        + " s inner join " + tableName2 + " t on s.a_string=t.a_string union all "
        + "select y.a_string, x.col1 from " + tableName2 + " x right join " + tableName1
        + " y on x.a_string=y.a_string";
      rs = conn.createStatement().executeQuery(ddl);
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(20, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(20, rs.getInt(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDerivedTable() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 10);
      stmt.execute();
      conn.commit();

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
      stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 20);
      stmt.execute();
      conn.commit();

      ddl = "select * from (select x.a_string, y.col1  from " + tableName1 + " x, " + tableName2
        + " y where x.a_string=y.a_string) union all "
        + "select * from (select t.a_string, s.col1 from " + tableName1 + " s, " + tableName2
        + " t where s.a_string=t.a_string)";
      ResultSet rs = conn.createStatement().executeQuery(ddl);
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(20, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testUnionAllInDerivedTable() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 10);
      stmt.execute();
      stmt.setString(1, "b");
      stmt.setInt(2, 20);
      stmt.execute();
      conn.commit();

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col2 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
      stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 30);
      stmt.execute();
      stmt.setString(1, "c");
      stmt.setInt(2, 60);
      stmt.execute();
      conn.commit();

      String query = "select a_string from " + "(select a_string, col1 from " + tableName1
        + " union all select a_string, col2 from " + tableName2 + " order by a_string)";
      ResultSet rs = conn.createStatement().executeQuery(query);
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertTrue(rs.next());
      assertEquals("b", rs.getString(1));
      assertTrue(rs.next());
      assertEquals("c", rs.getString(1));
      assertFalse(rs.next());

      query = "select c from " + "(select a_string, col1 c from " + tableName1
        + " union all select a_string, col2 c from " + tableName2 + " order by c)";
      rs = conn.createStatement().executeQuery(query);
      assertTrue(rs.next());
      assertEquals(10, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(20, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(30, rs.getInt(1));
      assertTrue(rs.next());
      assertEquals(60, rs.getInt(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testUnionAllInSubquery() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 10);
      stmt.execute();
      stmt.setString(1, "b");
      stmt.setInt(2, 20);
      stmt.execute();
      conn.commit();

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
      stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 30);
      stmt.execute();
      stmt.setString(1, "c");
      stmt.setInt(2, 60);
      stmt.execute();
      conn.commit();

      String[] queries = new String[2];
      queries[0] = "select a_string, col1 from " + tableName1 + " where a_string in "
        + "(select a_string aa from " + tableName2
        + " where a_string != 'a' union all select a_string bb from " + tableName2 + ")";
      queries[1] = "select a_string, col1 from " + tableName1
        + " where a_string in (select a_string from  " + "(select a_string from " + tableName2
        + " where a_string != 'a' union all select a_string from " + tableName2 + "))";
      for (String query : queries) {
        ResultSet rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a", rs.getString(1));
        assertEquals(10, rs.getInt(2));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testUnionAllWithBindParam() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);
      String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      stmt.setString(1, "a");
      stmt.setInt(2, 10);
      stmt.execute();
      conn.commit();

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);
      dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
      stmt = conn.prepareStatement(dml);
      stmt.setString(1, "b");
      stmt.setInt(2, 20);
      stmt.execute();
      conn.commit();

      ddl = "select a_string, col1 from " + tableName2
        + " where col1=? union all select a_string, col1 from " + tableName1 + " where col1=? ";
      stmt = conn.prepareStatement(ddl);
      stmt.setInt(1, 20);
      stmt.setInt(2, 10);
      ResultSet rs = stmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("b", rs.getString(1));
      assertEquals(20, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testExplainUnionAll() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);

    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.setAutoCommit(false);
      String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
        + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
      createTestTable(getUrl(), ddl);

      ddl = "select a_string, col1 from " + tableName1 + " union all select a_string, col1 from "
        + tableName2 + " order by col1 limit 1";
      ExplainPlan plan = conn.prepareStatement(ddl).unwrap(PhoenixPreparedStatement.class)
        .optimizeQuery().getExplainPlan();
      ExplainPlanAttributes explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals("UNION ALL OVER 2 QUERIES", explainPlanAttributes.getAbstractExplainPlan());
      assertEquals("PARALLEL 1-WAY", explainPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("FULL SCAN ", explainPlanAttributes.getExplainScanType());
      assertEquals(tableName1, explainPlanAttributes.getTableName());
      assertEquals("[COL1]", explainPlanAttributes.getServerSortedBy());
      assertEquals(1L, explainPlanAttributes.getServerRowLimit().longValue());
      assertEquals(1, explainPlanAttributes.getClientRowLimit().intValue());
      assertEquals("CLIENT MERGE SORT", explainPlanAttributes.getClientSortAlgo());
      ExplainPlanAttributes rhsPlanAttributes = explainPlanAttributes.getRhsJoinQueryExplainPlan();
      assertEquals("PARALLEL 1-WAY", rhsPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("FULL SCAN ", rhsPlanAttributes.getExplainScanType());
      assertEquals(tableName2, rhsPlanAttributes.getTableName());
      assertEquals("[COL1]", rhsPlanAttributes.getServerSortedBy());
      assertEquals(1L, rhsPlanAttributes.getServerRowLimit().longValue());
      assertEquals(1, rhsPlanAttributes.getClientRowLimit().intValue());
      assertEquals("CLIENT MERGE SORT", rhsPlanAttributes.getClientSortAlgo());

      String limitPlan = "UNION ALL OVER 2 QUERIES\n" + "    CLIENT SERIAL 1-WAY FULL SCAN OVER "
        + tableName1 + "\n" + "        SERVER 2 ROW LIMIT\n" + "    CLIENT 2 ROW LIMIT\n"
        + "    CLIENT SERIAL 1-WAY FULL SCAN OVER " + tableName2 + "\n"
        + "        SERVER 2 ROW LIMIT\n" + "    CLIENT 2 ROW LIMIT\n" + "CLIENT 2 ROW LIMIT";

      ddl = "select a_string, col1 from " + tableName1 + " union all select a_string, col1 from "
        + tableName2 + " limit 2";
      plan = conn.prepareStatement(ddl).unwrap(PhoenixPreparedStatement.class).optimizeQuery()
        .getExplainPlan();
      explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals("UNION ALL OVER 2 QUERIES", explainPlanAttributes.getAbstractExplainPlan());
      assertEquals("SERIAL 1-WAY", explainPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("FULL SCAN ", explainPlanAttributes.getExplainScanType());
      assertEquals(tableName1, explainPlanAttributes.getTableName());
      assertNull(explainPlanAttributes.getServerSortedBy());
      assertEquals(2L, explainPlanAttributes.getServerRowLimit().longValue());
      assertEquals(2, explainPlanAttributes.getClientRowLimit().intValue());
      rhsPlanAttributes = explainPlanAttributes.getRhsJoinQueryExplainPlan();
      assertEquals("SERIAL 1-WAY", rhsPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("FULL SCAN ", rhsPlanAttributes.getExplainScanType());
      assertEquals(tableName2, rhsPlanAttributes.getTableName());
      assertNull(rhsPlanAttributes.getServerSortedBy());
      assertEquals(2L, rhsPlanAttributes.getServerRowLimit().longValue());
      assertEquals(2, rhsPlanAttributes.getClientRowLimit().intValue());

      Statement stmt = conn.createStatement();
      stmt.setMaxRows(2);
      ResultSet rs = stmt.executeQuery("explain " + ddl);
      assertEquals(limitPlan, QueryUtil.getExplainPlan(rs));

      ddl = "select a_string, col1 from " + tableName1 + " union all select a_string, col1 from "
        + tableName2;
      plan = conn.prepareStatement(ddl).unwrap(PhoenixPreparedStatement.class).optimizeQuery()
        .getExplainPlan();
      explainPlanAttributes = plan.getPlanStepsAsAttributes();
      assertEquals("UNION ALL OVER 2 QUERIES", explainPlanAttributes.getAbstractExplainPlan());
      assertEquals("PARALLEL 1-WAY", explainPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("FULL SCAN ", explainPlanAttributes.getExplainScanType());
      assertEquals(tableName1, explainPlanAttributes.getTableName());
      rhsPlanAttributes = explainPlanAttributes.getRhsJoinQueryExplainPlan();
      assertEquals("PARALLEL 1-WAY", rhsPlanAttributes.getIteratorTypeAndScanSize());
      assertEquals("FULL SCAN ", rhsPlanAttributes.getExplainScanType());
      assertEquals(tableName2, rhsPlanAttributes.getTableName());
    }
  }

  @Test
  public void testBug2295() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    String itableName1 = generateUniqueName();
    String itableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      String ddl = "CREATE TABLE " + tableName1 + "("
        + "id BIGINT, col1 VARCHAR, col2 integer, CONSTRAINT pk PRIMARY KEY (id)) IMMUTABLE_ROWS=true";
      createTestTable(getUrl(), ddl);

      ddl = "CREATE TABLE " + tableName2 + "("
        + "id BIGINT, col1 VARCHAR, col2 integer, CONSTRAINT pk PRIMARY KEY (id)) IMMUTABLE_ROWS=true";
      createTestTable(getUrl(), ddl);

      ddl = "CREATE index " + itableName1 + " on " + tableName1 + "(col1)";
      createTestTable(getUrl(), ddl);

      ddl = "CREATE index " + itableName2 + " on " + tableName2 + "(col1)";
      createTestTable(getUrl(), ddl);

      ddl = "Explain SELECT /*+ INDEX(" + tableName1 + " " + itableName1 + ") */ col1, col2 from "
        + tableName1 + " where col1='123' " + "union all SELECT /*+ INDEX(" + tableName2 + " "
        + itableName2 + ") */ col1, col2 from " + tableName2 + " where col1='123'";
      ResultSet rs = conn.createStatement().executeQuery(ddl);
      assertTrue(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testBug7492() throws Exception {

    String accountTableDDL = "CREATE TABLE ACCOUNT (\n"
      + "ACCOUNT_IDENTIFIER VARCHAR(100) NOT NULL,\n" + "CRN_NUMBER_TEXT VARCHAR(100),\n"
      + "ORIGINAL_CURRENCY_CODE VARCHAR(100),\n" + "OUTSTANDING_BALANCE_AMOUNT DECIMAL(20,4),\n"
      + "SOURCE_SYSTEM_CODE VARCHAR(6) NOT NULL,\n" + "ACCOUNT_TYPE_CODE VARCHAR(100)\n"
      + "CONSTRAINT pk PRIMARY KEY (ACCOUNT_IDENTIFIER, SOURCE_SYSTEM_CODE)\n" + ") SALT_BUCKET=4";
    String exchangeTableDDL = "CREATE TABLE EXCHANGE_RATE (\n"
      + "CURRENCY_CODE VARCHAR(3) NOT NULL,\n" + "SPOT_RATE DECIMAL(15,9),\n"
      + "EXPANDED_SPOT_RATE DECIMAL(19,9),\n" + "RECORD_LAST_UPDATE_DATE TIMESTAMP\n"
      + "CONSTRAINT pk PRIMARY KEY (CURRENCY_CODE)\n" + ")SALT_BUCKETS=4";
    String customerTableDDL = "CREATE TABLE CUSTOMER (\n" + "CUSTOMER_IDENTIFIER VARCHAR(100),\n"
      + "SOURCE_SYSTEM_CODE VARCHAR(100) NOT NULL,\n" + "CRN_NUMBER_TEXT VARCHAR(100),\n"
      + "CONSTRAINT pk PRIMARY KEY (CUSTOMER_IDENTIFIER, SOURCE_SYSTEM_CODE)\n"
      + ") SALT_BUCKETS=4";

    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);

    try {
      createTestTable(getUrl(), accountTableDDL);
      createTestTable(getUrl(), exchangeTableDDL);
      createTestTable(getUrl(), customerTableDDL);

      String dml = "UPSERT INTO ACCOUNT VALUES ('ACC_1', 'CRN_1', 'IDR', 999, 'SRC_1', 'ATC_1')";
      conn.prepareStatement(dml).execute();

      dml = "UPSERT INTO EXCHANGE_RATE VALUES ('IDR', 0.53233436, 0.198919644, '2024-07-03')";
      conn.prepareStatement(dml).execute();

      dml = "UPSERT INTO CUSTOMER VALUES ('CUST_1', 'SRC_1','CRN_1')";
      conn.prepareStatement(dml).execute();

      conn.commit();

      String query = "select * from CUSTOMER";

      PreparedStatement pstmt = conn.prepareStatement(query);
      assertTrue(pstmt.getParameterMetaData() != null);
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("CUST_1", rs.getString(1));
      assertEquals("SRC_1", rs.getString(2));
      assertEquals("CRN_1", rs.getString(3));
      assertFalse(rs.next());

      query = "SELECT\n" + "    ca1.ORIGINAL_CURRENCY_CODE AS ORIGINAL_CURRENCY_CODE\n" + "FROM\n"
        + "    ACCOUNT ca1\n" + "LEFT JOIN EXCHANGE_RATE er1 ON\n"
        + "    ca1.ORIGINAL_CURRENCY_CODE = er1.CURRENCY_CODE\n" + "WHERE\n"
        + "    (ca1.ACCOUNT_IDENTIFIER,\n" + "    ca1.SOURCE_SYSTEM_CODE) IN (\n" + "    SELECT\n"
        + "        ca.ACCOUNT_IDENTIFIER,\n" + "        ca.SOURCE_SYSTEM_CODE\n" + "    FROM\n"
        + "        ACCOUNT ca\n" + "    WHERE\n" + "        ca.CRN_NUMBER_TEXT IN (\n"
        + "        SELECT\n" + "            CRN_NUMBER_TEXT\n" + "        FROM\n"
        + "            CUSTOMER\n" + "        WHERE\n"
        + "            CUSTOMER_IDENTIFIER = 'CUST_2'\n"
        + "        AND SOURCE_SYSTEM_CODE='SRC_1'))\n" + "AND ca1.ACCOUNT_TYPE_CODE IN ('ATC_1')\n"
        + "UNION ALL\n" + "SELECT\n" + "    ca1.ORIGINAL_CURRENCY_CODE AS ORIGINAL_CURRENCY_CODE\n"
        + "FROM\n" + "    ACCOUNT ca1\n" + "WHERE\n" + "    ca1.ACCOUNT_TYPE_CODE IN ('ATC_1')";
      pstmt = conn.prepareStatement(query);
      assertTrue(pstmt.getParameterMetaData() != null);
      rs = pstmt.executeQuery();

      assertTrue(rs.next());

    } finally {
      conn.close();
    }
  }

  @Test
  public void testParameterMetaDataNotNull() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);

    String ddl = "CREATE TABLE " + tableName1 + " " + "  (a_string varchar not null, col1 integer"
      + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
    createTestTable(getUrl(), ddl);
    String dml = "UPSERT INTO " + tableName1 + " VALUES(?, ?)";
    PreparedStatement stmt = conn.prepareStatement(dml);
    stmt.setString(1, "a");
    stmt.setInt(2, 10);
    stmt.execute();
    conn.commit();

    ddl = "CREATE TABLE " + tableName2 + " " + "  (a_string varchar not null, col1 integer"
      + "  CONSTRAINT pk PRIMARY KEY (a_string))\n";
    createTestTable(getUrl(), ddl);
    dml = "UPSERT INTO " + tableName2 + " VALUES(?, ?)";
    stmt = conn.prepareStatement(dml);
    stmt.setString(1, "b");
    stmt.setInt(2, 20);
    stmt.execute();
    conn.commit();

    String query = "select * from " + tableName1 + " union all select * from " + tableName2;

    try {
      PreparedStatement pstmt = conn.prepareStatement(query);
      assertTrue(pstmt.getParameterMetaData() != null);
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(10, rs.getInt(2));
      assertTrue(rs.next());
      assertEquals("b", rs.getString(1));
      assertEquals(20, rs.getInt(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDiffDataTypes() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    String tableName3 = generateUniqueName();
    String tableName4 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);

    String ddl = "create table " + tableName1 + " ( id bigint not null primary key, "
      + "firstname varchar(10), lastname varchar(10) )";
    createTestTable(getUrl(), ddl);
    String dml = "upsert into " + tableName1 + " values (?, ?, ?)";
    PreparedStatement stmt = conn.prepareStatement(dml);
    stmt.setInt(1, 1);
    stmt.setString(2, "john");
    stmt.setString(3, "doe");
    stmt.execute();
    stmt.setInt(1, 2);
    stmt.setString(2, "jane");
    stmt.setString(3, "doe");
    stmt.execute();
    conn.commit();

    ddl = "create table " + tableName2 + " ( id integer not null primary key, firstname char(12),"
      + " lastname varchar(12) )";
    createTestTable(getUrl(), ddl);
    dml = "upsert into " + tableName2 + " values (?, ?, ?)";
    stmt = conn.prepareStatement(dml);
    stmt.setInt(1, 1);
    stmt.setString(2, "sam");
    stmt.setString(3, "johnson");
    stmt.execute();
    stmt.setInt(1, 2);
    stmt.setString(2, "ann");
    stmt.setString(3, "wiely");
    stmt.execute();
    conn.commit();

    ddl = "create table " + tableName3 + " ( id varchar(20) not null primary key)";
    createTestTable(getUrl(), ddl);
    dml = "upsert into " + tableName3 + " values ('abcd')";
    stmt = conn.prepareStatement(dml);
    stmt.execute();
    conn.commit();
    ddl = "create table " + tableName4 + " ( id char(50) not null primary key)";
    createTestTable(getUrl(), ddl);
    dml = "upsert into " + tableName4 + " values ('xyz')";
    stmt = conn.prepareStatement(dml);
    stmt.execute();
    conn.commit();
    String query = "select id, 'foo' firstname, lastname from " + tableName1 + " union all"
      + " select * from " + tableName2;
    try {
      PreparedStatement pstmt = conn.prepareStatement(query);
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("foo", rs.getString(2));
      assertEquals("doe", rs.getString(3));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("foo", rs.getString(2));
      assertEquals("doe", rs.getString(3));
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("sam", rs.getString(2).trim());
      assertEquals("johnson", rs.getString(3));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("ann", rs.getString(2).trim());
      assertEquals("wiely", rs.getString(3));
      assertFalse(rs.next());

      pstmt = conn
        .prepareStatement("select * from " + tableName3 + " union all select * from " + tableName4);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("abcd", rs.getString(1));
      assertTrue(rs.next());
      assertEquals("xyz", rs.getString(1).trim());
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDiffScaleSortOrder() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    String tableName3 = generateUniqueName();
    String tableName4 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);

    String ddl = "create table " + tableName1 + " ( id bigint not null primary key desc, "
      + "firstname char(10), lastname varchar(10) )";
    createTestTable(getUrl(), ddl);
    String dml = "upsert into " + tableName1 + " values (?, ?, ?)";
    PreparedStatement stmt = conn.prepareStatement(dml);
    stmt.setInt(1, 1);
    stmt.setString(2, "john");
    stmt.setString(3, "doe");
    stmt.execute();
    stmt.setInt(1, 2);
    stmt.setString(2, "jane");
    stmt.setString(3, "doe");
    stmt.execute();
    conn.commit();

    ddl = "create table " + tableName2 + " ( id integer not null primary key asc, "
      + "firstname varchar(12), lastname varchar(10) )";
    createTestTable(getUrl(), ddl);
    dml = "upsert into " + tableName2 + " values (?, ?, ?)";
    stmt = conn.prepareStatement(dml);
    stmt.setInt(1, 1);
    stmt.setString(2, "sam");
    stmt.setString(3, "johnson");
    stmt.execute();
    stmt.setInt(1, 2);
    stmt.setString(2, "ann");
    stmt.setString(3, "wiely");
    stmt.execute();
    conn.commit();

    ddl = "create table " + tableName3 + " ( id varchar(20) not null primary key, col1 decimal)";
    createTestTable(getUrl(), ddl);
    dml = "upsert into " + tableName3 + " values ('abcd', 234.23)";
    stmt = conn.prepareStatement(dml);
    stmt.execute();
    conn.commit();
    ddl = "create table " + tableName4 + " ( id char(50) not null primary key, col1 decimal(12,4))";
    createTestTable(getUrl(), ddl);
    dml = "upsert into " + tableName4 + " values ('xyz', 1342.1234)";
    stmt = conn.prepareStatement(dml);
    stmt.execute();
    conn.commit();

    String query = "select * from " + tableName2 + " union all select * from " + tableName1;
    try {
      PreparedStatement pstmt = conn.prepareStatement(query);
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("sam", rs.getString(2));
      assertEquals("johnson", rs.getString(3));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("ann", rs.getString(2));
      assertEquals("wiely", rs.getString(3));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("jane", rs.getString(2).trim());
      assertEquals("doe", rs.getString(3));
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("john", rs.getString(2).trim());
      assertEquals("doe", rs.getString(3));
      assertFalse(rs.next());

      pstmt = conn
        .prepareStatement("select * from " + tableName3 + " union all select * from " + tableName4);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("abcd", rs.getString(1));
      assertEquals(BigDecimal.valueOf(234.2300), rs.getBigDecimal(2));
      assertTrue(rs.next());
      assertEquals("xyz", rs.getString(1).trim());
      assertEquals(BigDecimal.valueOf(1342.1234), rs.getBigDecimal(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testVarcharChar() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);

    String ddl = "create table " + tableName2 + " ( id integer not null primary key asc, "
      + "firstname char(8), lastname varchar )";
    createTestTable(getUrl(), ddl);
    String dml = "upsert into " + tableName2 + " values (?, ?, ?)";
    PreparedStatement stmt = conn.prepareStatement(dml);
    stmt.setInt(1, 1);
    stmt.setString(2, "sam");
    stmt.setString(3, "johnson");
    stmt.execute();
    stmt.setInt(1, 2);
    stmt.setString(2, "ann");
    stmt.setString(3, "wiely");
    stmt.execute();
    conn.commit();

    ddl = "create table " + tableName1 + " ( id bigint not null primary key desc, "
      + "firstname varchar(10), lastname char(10) )";
    createTestTable(getUrl(), ddl);
    dml = "upsert into " + tableName1 + " values (?, ?, ?)";
    stmt = conn.prepareStatement(dml);
    stmt.setInt(1, 1);
    stmt.setString(2, "john");
    stmt.setString(3, "doe");
    stmt.execute();
    stmt.setInt(1, 2);
    stmt.setString(2, "jane");
    stmt.setString(3, "doe");
    stmt.execute();
    conn.commit();

    String query = "select id, 'baa' firstname, lastname from " + tableName2 + " "
      + "union all select * from " + tableName1;
    try {
      PreparedStatement pstmt = conn.prepareStatement(query);
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("baa", rs.getString(2));
      assertEquals("johnson", rs.getString(3));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("baa", rs.getString(2));
      assertEquals("wiely", rs.getString(3));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("jane", rs.getString(2).trim());
      assertEquals("doe", rs.getString(3).trim());
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("john", rs.getString(2).trim());
      assertEquals("doe", rs.getString(3).trim());
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testCoerceExpr() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);

    String ddl = "create table " + tableName2 + " ( id integer not null primary key desc, "
      + "firstname char(8), lastname varchar, sales double)";
    createTestTable(getUrl(), ddl);
    String dml = "upsert into " + tableName2 + " values (?, ?, ?, ?)";
    PreparedStatement stmt = conn.prepareStatement(dml);
    stmt.setInt(1, 1);
    stmt.setString(2, "sam");
    stmt.setString(3, "johnson");
    stmt.setDouble(4, 100.6798);
    stmt.execute();
    stmt.setInt(1, 2);
    stmt.setString(2, "ann");
    stmt.setString(3, "wiely");
    stmt.setDouble(4, 10.67);
    stmt.execute();
    conn.commit();

    ddl = "create table " + tableName1 + " (id bigint not null primary key, "
      + "firstname char(10), lastname varchar(10), sales decimal)";
    createTestTable(getUrl(), ddl);
    dml = "upsert into " + tableName1 + " values (?, ?, ?, ?)";
    stmt = conn.prepareStatement(dml);
    stmt.setInt(1, 1);
    stmt.setString(2, "john");
    stmt.setString(3, "doe");
    stmt.setBigDecimal(4, BigDecimal.valueOf(467.894745));
    stmt.execute();
    stmt.setInt(1, 2);
    stmt.setString(2, "jane");
    stmt.setString(3, "doe");
    stmt.setBigDecimal(4, BigDecimal.valueOf(88.89474501));
    stmt.execute();
    conn.commit();

    String query = "select id, cast('foo' as char(10)) firstname, lastname, sales " + "from "
      + tableName1 + " union all select * from " + tableName2;
    try {
      PreparedStatement pstmt = conn.prepareStatement(query);
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("foo", rs.getString(2).trim());
      assertEquals("doe", rs.getString(3).trim());
      assertEquals(BigDecimal.valueOf(467.894745), rs.getBigDecimal(4));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("foo", rs.getString(2).trim());
      assertEquals("doe", rs.getString(3).trim());
      assertEquals(BigDecimal.valueOf(88.89474501), rs.getBigDecimal(4));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("ann", rs.getString(2).trim());
      assertEquals("wiely", rs.getString(3).trim());
      assertEquals(BigDecimal.valueOf(10.67), rs.getBigDecimal(4));
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("sam", rs.getString(2).trim());
      assertEquals("johnson", rs.getString(3).trim());
      assertEquals(BigDecimal.valueOf(100.6798), rs.getBigDecimal(4));
      assertFalse(rs.next());

      query = "select id, cast('foo' as char(10)) firstname, lastname, sales from " + tableName1;
      pstmt = conn.prepareStatement(query);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("foo", rs.getString(2).trim());
      assertEquals("doe", rs.getString(3));
      assertEquals(BigDecimal.valueOf(467.894745), rs.getBigDecimal(4));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("foo", rs.getString(2).trim());
      assertEquals("doe", rs.getString(3));
      assertEquals(BigDecimal.valueOf(88.89474501), rs.getBigDecimal(4));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testOrderByOptimizeBug7397() throws Exception {
    String tableName1 = generateUniqueName();
    String tableName2 = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    props.setProperty(QueryServices.FORCE_ROW_KEY_ORDER_ATTRIB, Boolean.toString(false));
    Connection conn = DriverManager.getConnection(getUrl(), props);

    try {
      String ddl = "create table " + tableName1 + "( " + " fuid UNSIGNED_LONG not null , "
        + " fstatsdate UNSIGNED_LONG not null, " + " fversion UNSIGNED_LONG not null,"
        + " faid_1 UNSIGNED_LONG not null," + " clk_pv_1 UNSIGNED_LONG, "
        + " activation_pv_1 UNSIGNED_LONG, " + " CONSTRAINT TEST_PK PRIMARY KEY ( " + " fuid , "
        + " fstatsdate, " + " fversion, " + " faid_1 " + " ))";
      createTestTable(getUrl(), ddl);
      String dml = "UPSERT INTO " + tableName1 + " VALUES(?,?,?,?,?,?)";
      PreparedStatement stmt = conn.prepareStatement(dml);
      setValues(stmt, 1, 20240711, 1, 11, 1, 1);
      stmt.execute();
      setValues(stmt, 1, 20240712, 1, 22, 3, 3);
      stmt.execute();
      setValues(stmt, 1, 20240713, 1, 33, 7, 7);
      stmt.execute();
      conn.commit();

      ddl = "create table " + tableName2 + "( " + " fuid UNSIGNED_LONG not null, "
        + " fstatsdate UNSIGNED_LONG not null, " + " fversion UNSIGNED_LONG not null,"
        + " faid_2 UNSIGNED_LONG not null," + " clk_pv_2 UNSIGNED_LONG, "
        + " activation_pv_2 UNSIGNED_LONG, " + " CONSTRAINT TEST_PK PRIMARY KEY ( " + " fuid , "
        + " fstatsdate, " + " fversion," + " faid_2 " + " ))";
      createTestTable(getUrl(), ddl);
      dml = "UPSERT INTO " + tableName2 + " VALUES(?,?,?,?,?,?)";
      stmt = conn.prepareStatement(dml);
      setValues(stmt, 1, 20240711, 1, 11, 6, 6);
      stmt.execute();
      setValues(stmt, 1, 20240712, 1, 22, 2, 2);
      stmt.execute();
      setValues(stmt, 1, 20240713, 1, 33, 4, 4);
      stmt.execute();
      setValues(stmt, 1, 20240710, 1, 22, 5, 5);
      stmt.execute();
      conn.commit();

      String orderedUnionSql = "(SELECT FUId AS advertiser_id," + "   FAId_1 AS adgroup_id,"
        + "   FStatsDate AS date," + "   SUM(clk_pv_1) AS valid_click_count,"
        + "   SUM(activation_pv_1) AS activated_count" + "  FROM " + tableName1
        + "  WHERE (FVersion = 1) AND (FUId IN (1)) AND (FAId_1 IN (11, 22, 33, 10))"
        + "  AND (FStatsDate >= 20240710) AND (FStatsDate <= 20240718)"
        + "  GROUP BY FUId, FAId_1, FStatsDate" + "  UNION ALL " + "  SELECT "
        + "  FUId AS advertiser_id," + "  FAId_2 AS adgroup_id," + "  FStatsDate AS date,"
        + "  SUM(clk_pv_2) AS valid_click_count," + "  SUM(activation_pv_2) AS activated_count"
        + "  FROM " + tableName2
        + "  WHERE (FVersion = 1) AND (FUId IN (1)) AND (FAId_2 IN (11, 22, 33, 10))"
        + "  AND (FStatsDate >= 20240710) AND (FStatsDate <= 20240718)"
        + "  GROUP BY FUId, FAId_2, FStatsDate" + ")";

      // Test group by orderPreserving
      String sql = "SELECT ADVERTISER_ID AS advertiser_id," + "ADGROUP_ID AS adgroup_id,"
        + "DATE AS i_date," + "SUM(VALID_CLICK_COUNT) AS valid_click_count,"
        + "SUM(ACTIVATED_COUNT) AS activated_count " + "FROM " + orderedUnionSql
        + "GROUP BY ADVERTISER_ID, ADGROUP_ID, I_DATE "
        + "ORDER BY advertiser_id, adgroup_id, i_date " + "limit 10";
      ResultSet rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs,
        new Long[][] { { 1L, 11L, 20240711L, 7L, 7L }, { 1L, 22L, 20240710L, 5L, 5L },
          { 1L, 22L, 20240712L, 5L, 5L }, { 1L, 33L, 20240713L, 11L, 11L } });

      // Test group by not orderPreserving
      sql = "SELECT ADVERTISER_ID AS i_advertiser_id," + "ADGROUP_ID AS i_adgroup_id,"
        + "SUM(VALID_CLICK_COUNT) AS valid_click_count,"
        + "SUM(ACTIVATED_COUNT) AS activated_count " + "FROM " + orderedUnionSql
        + "GROUP BY I_ADVERTISER_ID, ADGROUP_ID " + "ORDER BY i_adgroup_id " + "limit 10";
      rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs,
        new Long[][] { { 1L, 11L, 7L, 7L }, { 1L, 22L, 10L, 10L }, { 1L, 33L, 11L, 11L } });

      // Test group by not orderPreserving
      sql = "SELECT ADGROUP_ID AS adgroup_id," + "DATE AS i_date,"
        + "SUM(VALID_CLICK_COUNT) AS valid_click_count,"
        + "SUM(ACTIVATED_COUNT) AS activated_count " + "FROM " + orderedUnionSql
        + "GROUP BY ADGROUP_ID, I_DATE " + "ORDER BY adgroup_id, i_date " + "limit 10";
      rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs, new Long[][] { { 11L, 20240711L, 7L, 7L },
        { 22L, 20240710L, 5L, 5L }, { 22L, 20240712L, 5L, 5L }, { 33L, 20240713L, 11L, 11L } });

      // Test group by orderPreserving with where
      sql = "SELECT ADGROUP_ID AS adgroup_id," + "DATE AS i_date,"
        + "SUM(VALID_CLICK_COUNT) AS valid_click_count,"
        + "SUM(ACTIVATED_COUNT) AS activated_count " + "FROM " + orderedUnionSql
        + " where advertiser_id = 1 " + "GROUP BY ADGROUP_ID, I_DATE "
        + "ORDER BY adgroup_id, i_date " + "limit 10";
      rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs, new Long[][] { { 11L, 20240711L, 7L, 7L },
        { 22L, 20240710L, 5L, 5L }, { 22L, 20240712L, 5L, 5L }, { 33L, 20240713L, 11L, 11L } });

      // Test order by orderPreserving
      sql =
        "SELECT ADVERTISER_ID AS advertiser_id," + "ADGROUP_ID AS adgroup_id," + "DATE AS i_date,"
          + "VALID_CLICK_COUNT AS valid_click_count," + "ACTIVATED_COUNT AS activated_count "
          + "FROM " + orderedUnionSql + " where valid_click_count in (1, 5, 2, 4) "
          + "ORDER BY advertiser_id, i_date, adgroup_id " + "limit 10";
      rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs,
        new Long[][] { { 1L, 22L, 20240710L, 5L, 5L }, { 1L, 11L, 20240711L, 1L, 1L },
          { 1L, 22L, 20240712L, 2L, 2L }, { 1L, 33L, 20240713L, 4L, 4L } });

      // Test order by not orderPreserving
      sql = "SELECT ADVERTISER_ID AS advertiser_id," + "ADGROUP_ID AS i_adgroup_id,"
        + "DATE AS date," + "VALID_CLICK_COUNT AS valid_click_count,"
        + "ACTIVATED_COUNT AS activated_count " + "FROM " + orderedUnionSql
        + "ORDER BY advertiser_id, i_adgroup_id, date, valid_click_count " + "limit 10";
      rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs,
        new Long[][] { { 1L, 11L, 20240711L, 1L, 1L }, { 1L, 11L, 20240711L, 6L, 6L },
          { 1L, 22L, 20240710L, 5L, 5L }, { 1L, 22L, 20240712L, 2L, 2L },
          { 1L, 22L, 20240712L, 3L, 3L }, { 1L, 33L, 20240713L, 4L, 4L },
          { 1L, 33L, 20240713L, 7L, 7L } });

      // Test there is no order in union
      sql = "SELECT ADVERTISER_ID AS advertiser_id," + "ADGROUP_ID AS adgroup_id,"
        + "DATE AS i_date," + "SUM(VALID_CLICK_COUNT) AS valid_click_count,"
        + "SUM(ACTIVATED_COUNT) AS activated_count " + "FROM " + "(SELECT FUId AS advertiser_id,"
        + "   FAId_1 AS adgroup_id," + "   FStatsDate AS date,"
        + "   clk_pv_1 AS valid_click_count," + "   activation_pv_1 AS activated_count" + "  FROM "
        + tableName1 + "  WHERE (FVersion = 1) AND (FUId IN (1)) AND (FAId_1 IN (11, 22, 33, 10))"
        + "  AND (FStatsDate >= 20240710) AND (FStatsDate <= 20240718)" + "  UNION ALL "
        + "  SELECT " + "  FUId AS advertiser_id," + "  FAId_2 AS adgroup_id,"
        + "  FStatsDate AS date," + "  clk_pv_2 AS valid_click_count,"
        + "  activation_pv_2 AS activated_count" + "  FROM " + tableName2
        + "  WHERE (FVersion = 1) AND (FUId IN (1)) AND (FAId_2 IN (11, 22, 33, 10))"
        + "  AND (FStatsDate >= 20240710) AND (FStatsDate <= 20240718)" + ")"
        + "GROUP BY ADVERTISER_ID, ADGROUP_ID, I_DATE "
        + "ORDER BY advertiser_id, adgroup_id, i_date " + "limit 10";
      rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs,
        new Long[][] { { 1L, 11L, 20240711L, 7L, 7L }, { 1L, 22L, 20240710L, 5L, 5L },
          { 1L, 22L, 20240712L, 5L, 5L }, { 1L, 33L, 20240713L, 11L, 11L } });

      // Test alias not inconsistent in union
      sql = "SELECT ADVERTISER_ID AS advertiser_id," + "ADGROUP_ID_1 AS adgroup_id,"
        + "DATE AS i_date," + "SUM(VALID_CLICK_COUNT) AS valid_click_count,"
        + "SUM(ACTIVATED_COUNT) AS activated_count " + "FROM " + "(SELECT FUId AS advertiser_id,"
        + "   FAId_1 AS adgroup_id_1," + "   FStatsDate AS date,"
        + "   SUM(clk_pv_1) AS valid_click_count," + "   SUM(activation_pv_1) AS activated_count"
        + "  FROM " + tableName1
        + "  WHERE (FVersion = 1) AND (FUId IN (1)) AND (FAId_1 IN (11, 22, 33, 10))"
        + "  AND (FStatsDate >= 20240710) AND (FStatsDate <= 20240718)"
        + "  GROUP BY FUId, FAId_1, FStatsDate" + "  UNION ALL " + "  SELECT "
        + "  FUId AS advertiser_id," + "  FAId_2," + "  FStatsDate AS date," + "  SUM(clk_pv_2),"
        + "  SUM(activation_pv_2)" + "  FROM " + tableName2
        + "  WHERE (FVersion = 1) AND (FUId IN (1)) AND (FAId_2 IN (11, 22, 33, 10))"
        + "  AND (FStatsDate >= 20240710) AND (FStatsDate <= 20240718)"
        + "  GROUP BY FUId, FAId_2, FStatsDate" + ")"
        + "GROUP BY ADVERTISER_ID, ADGROUP_ID_1, I_DATE "
        + "ORDER BY advertiser_id, adgroup_id, i_date " + "limit 10";
      rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs,
        new Long[][] { { 1L, 11L, 20240711L, 7L, 7L }, { 1L, 22L, 20240710L, 5L, 5L },
          { 1L, 22L, 20240712L, 5L, 5L }, { 1L, 33L, 20240713L, 11L, 11L } });

      // Test order by column not equals in union
      sql = "SELECT ADVERTISER_ID AS advertiser_id," + "ADGROUP_ID AS adgroup_id,"
        + "DATE AS i_date," + "SUM(VALID_CLICK_COUNT) AS valid_click_count,"
        + "SUM(ACTIVATED_COUNT) AS activated_count " + "FROM " + "(SELECT FUId AS advertiser_id,"
        + "   FAId_1 AS adgroup_id," + "   FStatsDate AS date,"
        + "   SUM(clk_pv_1) AS valid_click_count," + "   SUM(activation_pv_1) AS activated_count"
        + "  FROM " + tableName1
        + "  WHERE (FVersion = 1) AND (FUId IN (1)) AND (FAId_1 IN (11, 22, 33, 10))"
        + "  AND (FStatsDate >= 20240710) AND (FStatsDate <= 20240718)"
        + "  GROUP BY FUId, FAId_1, FStatsDate" + "  UNION ALL " + "  SELECT "
        + "  FUId AS advertiser_id," + "  FAId_2 AS adgroup_id,"
        + "  cast (0 as UNSIGNED_LONG)  AS date," + "  SUM(clk_pv_2) AS valid_click_count,"
        + "  SUM(activation_pv_2) AS activated_count" + "  FROM " + tableName2
        + "  WHERE (FVersion = 1) AND (FUId IN (1)) AND (FAId_2 IN (11, 22, 33, 10))"
        + "  AND (FStatsDate >= 20240710) AND (FStatsDate <= 20240718)" + "  GROUP BY FUId, FAId_2"
        + ")" + "GROUP BY ADVERTISER_ID, ADGROUP_ID, I_DATE "
        + "ORDER BY advertiser_id, adgroup_id, i_date " + "limit 10";
      rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs,
        new Long[][] { { 1L, 11L, 0L, 6L, 6L }, { 1L, 11L, 20240711L, 1L, 1L },
          { 1L, 22L, 0L, 7L, 7L }, { 1L, 22L, 20240712L, 3L, 3L }, { 1L, 33L, 0L, 4L, 4L },
          { 1L, 33L, 20240713L, 7L, 7L } });

      // Test only union and order by not match
      sql = orderedUnionSql.substring(1, orderedUnionSql.length() - 1)
        + " order by advertiser_id, date, adgroup_id, valid_click_count";
      rs = conn.createStatement().executeQuery(sql);
      TestUtil.assertResultSet(rs,
        new Long[][] { { 1L, 22L, 20240710L, 5L, 5L }, { 1L, 11L, 20240711L, 1L, 1L },
          { 1L, 11L, 20240711L, 6L, 6L }, { 1L, 22L, 20240712L, 2L, 2L },
          { 1L, 22L, 20240712L, 3L, 3L }, { 1L, 33L, 20240713L, 4L, 4L },
          { 1L, 33L, 20240713L, 7L, 7L } });
    } finally {
      conn.close();
    }
  }

  private static void setValues(PreparedStatement stmt, int... values) throws SQLException {
    for (int i = 0; i < values.length; i++) {
      stmt.setLong(i + 1, values[i]);
    }
  }
}
