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

import static org.apache.phoenix.query.QueryConstants.MILLIS_IN_DAY;
import static org.apache.phoenix.util.TestUtil.ATABLE_NAME;
import static org.apache.phoenix.util.TestUtil.A_VALUE;
import static org.apache.phoenix.util.TestUtil.B_VALUE;
import static org.apache.phoenix.util.TestUtil.C_VALUE;
import static org.apache.phoenix.util.TestUtil.ROW1;
import static org.apache.phoenix.util.TestUtil.ROW2;
import static org.apache.phoenix.util.TestUtil.ROW3;
import static org.apache.phoenix.util.TestUtil.ROW4;
import static org.apache.phoenix.util.TestUtil.ROW5;
import static org.apache.phoenix.util.TestUtil.ROW6;
import static org.apache.phoenix.util.TestUtil.ROW7;
import static org.apache.phoenix.util.TestUtil.ROW8;
import static org.apache.phoenix.util.TestUtil.ROW9;
import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.apache.phoenix.util.TestUtil.assertResultSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Properties;
import org.apache.phoenix.util.PropertiesUtil;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(ParallelStatsDisabledTest.class)
public class DistinctCountIT extends ParallelStatsDisabledIT {

  @Test
  public void testDistinctCountOnColumn() throws Exception {
    String tenantId = getOrganizationId();
    String tableName = generateUniqueName();
    initATableValues(tenantId, null, getDefaultSplits(tenantId), null, tableName);

    String query = "SELECT count(DISTINCT A_STRING) FROM " + tableName;
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(3, rs.getLong(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDistinctCountOnRKColumn() throws Exception {
    String tenantId = getOrganizationId();
    String tableName = generateUniqueName();
    initATableValues(tenantId, null, getDefaultSplits(tenantId), null, tableName);

    String query = "SELECT count(DISTINCT ORGANIZATION_ID) FROM " + tableName;

    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getLong(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDistinctCountWithGroupBy() throws Exception {
    String tenantId = getOrganizationId();
    String tableName = generateUniqueName();
    initATableValues(tenantId, null, getDefaultSplits(tenantId), null, tableName);

    String query =
      "SELECT A_STRING, count(DISTINCT B_STRING) FROM " + tableName + " group by A_STRING";

    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(A_VALUE, rs.getString(1));
      assertEquals(2, rs.getLong(2));
      assertTrue(rs.next());
      assertEquals(B_VALUE, rs.getString(1));
      assertEquals(1, rs.getLong(2));
      assertTrue(rs.next());
      assertEquals(C_VALUE, rs.getString(1));
      assertEquals(1, rs.getLong(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDistinctCountWithGroupByAndOrderBy() throws Exception {
    String tenantId = getOrganizationId();
    String tableName = generateUniqueName();
    initATableValues(tenantId, null, getDefaultSplits(tenantId), null, tableName);

    String query = "SELECT A_STRING, count(DISTINCT B_STRING) FROM " + tableName
      + " group by A_STRING order by A_STRING desc";

    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(C_VALUE, rs.getString(1));
      assertEquals(1, rs.getLong(2));
      assertTrue(rs.next());
      assertEquals(B_VALUE, rs.getString(1));
      assertEquals(1, rs.getLong(2));
      assertTrue(rs.next());
      assertEquals(A_VALUE, rs.getString(1));
      assertEquals(2, rs.getLong(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDistinctCountWithGroupByAndOrderByOnDistinctCount() throws Exception {
    String tenantId = getOrganizationId();
    String tableName = generateUniqueName();
    initATableValues(tenantId, null, getDefaultSplits(tenantId), null, tableName);

    String query = "SELECT A_STRING, count(DISTINCT B_STRING) as COUNT_B_STRING FROM " + tableName
      + " group by A_STRING order by COUNT_B_STRING";

    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(B_VALUE, rs.getString(1));
      assertEquals(1, rs.getLong(2));
      assertTrue(rs.next());
      assertEquals(C_VALUE, rs.getString(1));
      assertEquals(1, rs.getLong(2));
      assertTrue(rs.next());
      assertEquals(A_VALUE, rs.getString(1));
      assertEquals(2, rs.getLong(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDistinctCountWithGroupByOrdered() throws Exception {
    String tenantId = getOrganizationId();
    String tenantId2 = "00D400000000XHP";
    String tableName = generateUniqueName();
    initATableValues(tenantId, tenantId2, getDefaultSplits(tenantId), null, tableName);

    String query = "SELECT organization_id, count(DISTINCT A_STRING) FROM " + tableName
      + " group by organization_id";

    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(tenantId, rs.getString(1));
      assertEquals(3, rs.getLong(2));
      assertTrue(rs.next());
      assertEquals(tenantId2, rs.getString(1));
      assertEquals(1, rs.getLong(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDistinctCountOn2Columns() throws Exception {
    String tenantId = getOrganizationId();
    String tableName = generateUniqueName();
    initATableValues(tenantId, null, getDefaultSplits(tenantId), null, tableName);

    String query = "SELECT count(DISTINCT A_STRING), count(DISTINCT B_STRING) FROM " + tableName;

    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(3, rs.getLong(1));
      assertEquals(2, rs.getLong(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDistinctCountONE() throws Exception {
    String tenantId = getOrganizationId();
    String tableName = generateUniqueName();
    initATableValues(tenantId, null, getDefaultSplits(tenantId), null, tableName);

    String query = "SELECT count(DISTINCT 1) FROM " + tableName;

    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getLong(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDistinctCountOneWithEmptyResult() throws Exception {
    String tenantId = getOrganizationId();
    String tableName = generateUniqueName();
    initATableValues(null, null, getDefaultSplits(tenantId), null, tableName);

    String query = "SELECT count(DISTINCT 1) FROM " + tableName;

    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(0, rs.getLong(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  protected static void initATableValues(String tenantId1, String tenantId2, byte[][] splits,
    Date date, String tableName) throws Exception {
    ensureTableCreated(getUrl(), tableName, ATABLE_NAME, splits, null);

    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      // Insert all rows at ts
      PreparedStatement stmt = conn.prepareStatement("upsert into " + tableName + " ("
        + "    ORGANIZATION_ID, " + "    ENTITY_ID, " + "    A_STRING, " + "    B_STRING, "
        + "    A_INTEGER, " + "    A_DATE, " + "    X_DECIMAL, " + "    X_LONG, " + "    X_INTEGER,"
        + "    Y_INTEGER)" + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
      if (tenantId1 != null) {
        stmt.setString(1, tenantId1);
        stmt.setString(2, ROW1);
        stmt.setString(3, A_VALUE);
        stmt.setString(4, B_VALUE);
        stmt.setInt(5, 1);
        stmt.setDate(6, date);
        stmt.setBigDecimal(7, null);
        stmt.setNull(8, Types.BIGINT);
        stmt.setNull(9, Types.INTEGER);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();

        stmt.setString(1, tenantId1);
        stmt.setString(2, ROW2);
        stmt.setString(3, A_VALUE);
        stmt.setString(4, C_VALUE);
        stmt.setInt(5, 2);
        stmt.setDate(6, date == null ? null : new Date(date.getTime() + MILLIS_IN_DAY * 1));
        stmt.setBigDecimal(7, null);
        stmt.setNull(8, Types.BIGINT);
        stmt.setNull(9, Types.INTEGER);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();

        stmt.setString(1, tenantId1);
        stmt.setString(2, ROW3);
        stmt.setString(3, A_VALUE);
        stmt.setString(4, C_VALUE);
        stmt.setInt(5, 3);
        stmt.setDate(6, date == null ? null : new Date(date.getTime() + MILLIS_IN_DAY * 2));
        stmt.setBigDecimal(7, null);
        stmt.setNull(8, Types.BIGINT);
        stmt.setNull(9, Types.INTEGER);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();

        stmt.setString(1, tenantId1);
        stmt.setString(2, ROW4);
        stmt.setString(3, A_VALUE);
        stmt.setString(4, B_VALUE);
        stmt.setInt(5, 4);
        stmt.setDate(6, date == null ? null : date);
        stmt.setBigDecimal(7, null);
        stmt.setNull(8, Types.BIGINT);
        stmt.setNull(9, Types.INTEGER);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();

        stmt.setString(1, tenantId1);
        stmt.setString(2, ROW5);
        stmt.setString(3, B_VALUE);
        stmt.setString(4, C_VALUE);
        stmt.setInt(5, 5);
        stmt.setDate(6, date == null ? null : new Date(date.getTime() + MILLIS_IN_DAY * 1));
        stmt.setBigDecimal(7, null);
        stmt.setNull(8, Types.BIGINT);
        stmt.setNull(9, Types.INTEGER);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();

        stmt.setString(1, tenantId1);
        stmt.setString(2, ROW6);
        stmt.setString(3, B_VALUE);
        stmt.setString(4, C_VALUE);
        stmt.setInt(5, 6);
        stmt.setDate(6, date == null ? null : new Date(date.getTime() + MILLIS_IN_DAY * 2));
        stmt.setBigDecimal(7, null);
        stmt.setNull(8, Types.BIGINT);
        stmt.setNull(9, Types.INTEGER);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();

        stmt.setString(1, tenantId1);
        stmt.setString(2, ROW7);
        stmt.setString(3, B_VALUE);
        stmt.setString(4, C_VALUE);
        stmt.setInt(5, 7);
        stmt.setDate(6, date == null ? null : date);
        stmt.setBigDecimal(7, BigDecimal.valueOf(0.1));
        stmt.setLong(8, 5L);
        stmt.setInt(9, 5);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();

        stmt.setString(1, tenantId1);
        stmt.setString(2, ROW8);
        stmt.setString(3, B_VALUE);
        stmt.setString(4, C_VALUE);
        stmt.setInt(5, 8);
        stmt.setDate(6, date == null ? null : new Date(date.getTime() + MILLIS_IN_DAY * 1));
        stmt.setBigDecimal(7, BigDecimal.valueOf(3.9));
        long l = Integer.MIN_VALUE - 1L;
        assert (l < Integer.MIN_VALUE);
        stmt.setLong(8, l);
        stmt.setInt(9, 4);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();

        stmt.setString(1, tenantId1);
        stmt.setString(2, ROW9);
        stmt.setString(3, C_VALUE);
        stmt.setString(4, C_VALUE);
        stmt.setInt(5, 9);
        stmt.setDate(6, date == null ? null : new Date(date.getTime() + MILLIS_IN_DAY * 2));
        stmt.setBigDecimal(7, BigDecimal.valueOf(3.3));
        l = Integer.MAX_VALUE + 1L;
        assert (l > Integer.MAX_VALUE);
        stmt.setLong(8, l);
        stmt.setInt(9, 3);
        stmt.setInt(10, 300);
        stmt.execute();
      }
      if (tenantId2 != null) {
        stmt.setString(1, tenantId2);
        stmt.setString(2, ROW1);
        stmt.setString(3, A_VALUE);
        stmt.setString(4, B_VALUE);
        stmt.setInt(5, 1);
        stmt.setDate(6, date);
        stmt.setBigDecimal(7, null);
        stmt.setNull(8, Types.BIGINT);
        stmt.setNull(9, Types.INTEGER);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();

        stmt.setString(1, tenantId2);
        stmt.setString(2, ROW2);
        stmt.setString(3, A_VALUE);
        stmt.setString(4, C_VALUE);
        stmt.setInt(5, 2);
        stmt.setDate(6, date == null ? null : new Date(date.getTime() + MILLIS_IN_DAY * 1));
        stmt.setBigDecimal(7, null);
        stmt.setNull(8, Types.BIGINT);
        stmt.setNull(9, Types.INTEGER);
        stmt.setNull(10, Types.INTEGER);
        stmt.execute();
      }
      conn.commit();
    } finally {
      conn.close();
    }
  }

  @Test
  public void testDistinctCountOnIndexTab() throws Exception {
    String tableName = generateUniqueName();
    String indexName = generateUniqueName();
    String ddl = "create table " + tableName + " (id integer not null, first_name char(15),\n"
      + "    last_name char(15), CONSTRAINT pk PRIMARY KEY (id))";
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(getUrl(), props);
    PreparedStatement stmt = conn.prepareStatement(ddl);
    stmt.execute(ddl);

    conn.createStatement()
      .execute("CREATE INDEX " + indexName + " ON " + tableName + "(first_name)");

    stmt = conn.prepareStatement(
      "upsert into " + tableName + " (id, first_name, " + "last_name) VALUES (?, ?, ?)");
    stmt.setInt(1, 1);
    stmt.setString(2, "NAME1");
    stmt.setString(3, "LN");
    stmt.execute();
    stmt.setInt(1, 2);
    stmt.setString(2, "NAME1");
    stmt.setString(3, "LN2");
    stmt.execute();
    stmt.setInt(1, 3);
    stmt.setString(2, "NAME2");
    stmt.setString(3, "LN3");
    stmt.execute();
    conn.commit();

    String query = "SELECT COUNT (DISTINCT first_name) FROM " + tableName;
    PreparedStatement statement = conn.prepareStatement(query);
    ResultSet rs = statement.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    conn.close();
  }

  @Test
  public void testDistinctCountLimitBug5217() throws Exception {
    Connection conn = null;
    try {
      Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
      conn = DriverManager.getConnection(getUrl(), props);
      String tableName = generateUniqueName();
      String sql = "create table " + tableName + "( " + " pk1 integer not null , "
        + " pk2 integer not null, " + " v integer, " + " CONSTRAINT TEST_PK PRIMARY KEY (pk1,pk2))";
      conn.createStatement().execute(sql);
      conn.createStatement().execute("UPSERT INTO " + tableName + "(pk1,pk2,v) VALUES (1,1,1)");
      conn.createStatement().execute("UPSERT INTO " + tableName + "(pk1,pk2,v) VALUES (2,2,2)");
      conn.commit();

      sql = "select count(distinct pk1) from " + tableName + " limit 1";
      ResultSet rs = conn.prepareStatement(sql).executeQuery();
      assertResultSet(rs, new Object[][] { { Long.valueOf(2L) } });
    } finally {
      if (conn != null) {
        conn.close();
      }
    }
  }
}
