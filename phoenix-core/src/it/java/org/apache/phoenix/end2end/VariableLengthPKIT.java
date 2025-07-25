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
import static org.apache.phoenix.util.TestUtil.BTABLE_NAME;
import static org.apache.phoenix.util.TestUtil.PTSDB2_NAME;
import static org.apache.phoenix.util.TestUtil.PTSDB_NAME;
import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.Format;
import java.util.Properties;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.schema.ConstraintViolationException;
import org.apache.phoenix.util.DateUtil;
import org.apache.phoenix.util.PropertiesUtil;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(ParallelStatsDisabledTest.class)
public class VariableLengthPKIT extends ParallelStatsDisabledIT {
  private static final String DS1 = "1970-01-01 00:58:00";
  private static final Date D1 = toDate(DS1);

  private static Date toDate(String dateString) {
    return DateUtil.parseDate(dateString);
  }

  protected static void initGroupByRowKeyColumns(String pTSDBtableName) throws Exception {
    ensureTableCreated(getUrl(), pTSDBtableName, PTSDB_NAME, null, null, null);
    // Insert all rows at ts
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    PreparedStatement stmt = conn.prepareStatement("upsert into " + pTSDBtableName + " ("
      + "    INST, " + "    HOST," + "    \"DATE\")" + "VALUES (?, ?, CURRENT_DATE())");
    stmt.setString(1, "ab");
    stmt.setString(2, "a");
    stmt.execute();
    stmt.setString(1, "ac");
    stmt.setString(2, "b");
    stmt.execute();
    stmt.setString(1, "ad");
    stmt.setString(2, "a");
    stmt.execute();
    conn.commit();
    conn.close();
  }

  private static void initVarcharKeyTableValues(byte[][] splits, String varcharKeyTestTableName)
    throws Exception {
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);

    String ddl = "create table " + varcharKeyTestTableName + "   (pk varchar not null primary key)";
    createTestTable(getUrl(), ddl, splits, null);
    PreparedStatement stmt =
      conn.prepareStatement("upsert into " + varcharKeyTestTableName + "(pk) " + "VALUES (?)");
    stmt.setString(1, "   def");
    stmt.execute();
    stmt.setString(1, "jkl   ");
    stmt.execute();
    stmt.setString(1, "   ghi   ");
    stmt.execute();

    conn.commit();
    conn.close();
  }

  private static void initPTSDBTableValues(byte[][] splits, String pTSDBtableName)
    throws Exception {
    ensureTableCreated(getUrl(), pTSDBtableName, PTSDB_NAME, splits, null, null);
    // Insert all rows at ts
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    PreparedStatement stmt = conn.prepareStatement("upsert into " + pTSDBtableName + " ("
      + "    INST, " + "    HOST," + "    \"DATE\"," + "    VAL)" + "VALUES (?, ?, ?, ?)");
    stmt.setString(1, "abc");
    stmt.setString(2, "abc-def-ghi");
    stmt.setDate(3, new Date(System.currentTimeMillis()));
    stmt.setBigDecimal(4, new BigDecimal(.5));
    stmt.execute();
    conn.close();
  }

  private static void initBTableValues(byte[][] splits, String bTableName) throws Exception {
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    ensureTableCreated(getUrl(), bTableName, BTABLE_NAME, splits, null, null);

    PreparedStatement stmt = conn.prepareStatement("upsert into " + bTableName + " ("
      + "    A_STRING, " + "    A_ID," + "    B_STRING," + "    A_INTEGER," + "    B_INTEGER,"
      + "    C_INTEGER," + "    D_STRING," + "    E_STRING)" + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
    stmt.setString(1, "abc");
    stmt.setString(2, "111");
    stmt.setString(3, "x");
    stmt.setInt(4, 1);
    stmt.setInt(5, 10);
    stmt.setInt(6, 1000);
    stmt.setString(7, null);
    stmt.setString(8, "0123456789");
    stmt.execute();

    stmt.setString(1, "abcd");
    stmt.setString(2, "222");
    stmt.setString(3, "xy");
    stmt.setInt(4, 2);
    stmt.setNull(5, Types.INTEGER);
    stmt.setNull(6, Types.INTEGER);
    stmt.execute();

    stmt.setString(3, "xyz");
    stmt.setInt(4, 3);
    stmt.setInt(5, 10);
    stmt.setInt(6, 1000);
    stmt.setString(7, "efg");
    stmt.execute();

    stmt.setString(3, "xyzz");
    stmt.setInt(4, 4);
    stmt.setInt(5, 40);
    stmt.setNull(6, Types.INTEGER);
    stmt.setString(7, null);
    stmt.execute();

    conn.commit();
    conn.close();
  }

  @Test
  public void testSingleColumnScanKey() throws Exception {
    String bTableName = generateUniqueName();
    String query = "SELECT A_STRING,substr(a_id,1,1),B_STRING,A_INTEGER,B_INTEGER FROM "
      + bTableName + " WHERE A_STRING=?";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      statement.setString(1, "abc");
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abc", rs.getString(1));
      assertEquals("1", rs.getString(2));
      assertEquals("x", rs.getString(3));
      assertEquals(1, rs.getInt(4));
      assertEquals(10, rs.getInt(5));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSingleColumnGroupBy() throws Exception {
    String pTSDBTableName = generateUniqueName();
    String query = "SELECT INST FROM " + pTSDBTableName + " GROUP BY INST";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);

    try {
      initPTSDBTableValues(null, pTSDBTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abc", rs.getString(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testNonfirstColumnGroupBy() throws Exception {
    String pTSDBTableName = generateUniqueName();
    String query = "SELECT HOST FROM " + pTSDBTableName + " WHERE INST='abc' GROUP BY HOST";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initPTSDBTableValues(null, pTSDBTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abc-def-ghi", rs.getString(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testGroupByRowKeyColumns() throws Exception {
    String pTSDBTableName = generateUniqueName();
    String query =
      "SELECT SUBSTR(INST,1,1),HOST FROM " + pTSDBTableName + " GROUP BY SUBSTR(INST,1,1),HOST";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initGroupByRowKeyColumns(pTSDBTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals("a", rs.getString(2));
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals("b", rs.getString(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSkipScan() throws Exception {
    String pTSDBTableName = generateUniqueName();
    String query = "SELECT HOST FROM " + pTSDBTableName
      + " WHERE INST='abc' AND \"DATE\">=TO_DATE('1970-01-01 00:00:00') AND \"DATE\" <TO_DATE('2171-01-01 00:00:00')";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initPTSDBTableValues(null, pTSDBTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abc-def-ghi", rs.getString(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSkipMax() throws Exception {
    String pTSDBTableName = generateUniqueName();
    String query = "SELECT MAX(INST),MAX(\"DATE\") FROM " + pTSDBTableName
      + " WHERE INST='abc' AND \"DATE\">=TO_DATE('1970-01-01 00:00:00') AND \"DATE\" <TO_DATE('2171-01-01 00:00:00')";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initPTSDBTableValues(null, pTSDBTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abc", rs.getString(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSkipMaxWithLimit() throws Exception {
    String pTSDBTableName = generateUniqueName();
    String query = "SELECT MAX(INST),MAX(\"DATE\") FROM " + pTSDBTableName
      + " WHERE INST='abc' AND \"DATE\">=TO_DATE('1970-01-01 00:00:00') AND \"DATE\" <TO_DATE('2171-01-01 00:00:00') LIMIT 2";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initPTSDBTableValues(null, pTSDBTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abc", rs.getString(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSingleColumnKeyFilter() throws Exception {
    String bTableName = generateUniqueName();
    // Requires not null column to be projected, since the only one projected in the query is
    // nullable and will cause the no key value to be returned if it is the only one projected.
    String query = "SELECT A_STRING,substr(a_id,1,1),B_STRING,A_INTEGER,B_INTEGER FROM "
      + bTableName + " WHERE B_STRING=?";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      statement.setString(1, "xy");
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abcd", rs.getString(1));
      assertEquals("2", rs.getString(2));
      assertEquals("xy", rs.getString(3));
      assertEquals(2, rs.getInt(4));
      assertEquals(0, rs.getInt(5));
      assertTrue(rs.wasNull());
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testMultiColumnEqScanKey() throws Exception {
    String bTableName = generateUniqueName();
    String query = "SELECT A_STRING,substr(a_id,1,1),B_STRING,A_INTEGER,B_INTEGER FROM "
      + bTableName + " WHERE A_STRING=? AND A_ID=? AND B_STRING=?";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      statement.setString(1, "abcd");
      statement.setString(2, "222");
      statement.setString(3, "xy");
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abcd", rs.getString(1));
      assertEquals("2", rs.getString(2));
      assertEquals("xy", rs.getString(3));
      assertEquals(2, rs.getInt(4));
      assertEquals(0, rs.getInt(5));
      assertTrue(rs.wasNull());
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testMultiColumnGTScanKey() throws Exception {
    String bTableName = generateUniqueName();
    String query = "SELECT A_STRING,substr(a_id,1,1),B_STRING,A_INTEGER,B_INTEGER FROM "
      + bTableName + " WHERE A_STRING=? AND A_ID=? AND B_STRING>?";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      statement.setString(1, "abcd");
      statement.setString(2, "222");
      statement.setString(3, "xy");
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abcd", rs.getString(1));
      assertEquals("2", rs.getString(2));
      assertEquals("xyz", rs.getString(3));
      assertEquals(3, rs.getInt(4));
      assertEquals(10, rs.getInt(5));
      assertTrue(rs.next());
      assertEquals("abcd", rs.getString(1));
      assertEquals("2", rs.getString(2));
      assertEquals("xyzz", rs.getString(3));
      assertEquals(4, rs.getInt(4));
      assertEquals(40, rs.getInt(5));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testMultiColumnGTKeyFilter() throws Exception {
    String bTableName = generateUniqueName();
    String query = "SELECT A_STRING,substr(a_id,1,1),B_STRING,A_INTEGER,B_INTEGER FROM "
      + bTableName + " WHERE A_STRING>? AND A_INTEGER>=?";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      statement.setString(1, "abc");
      statement.setInt(2, 4);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("abcd", rs.getString(1));
      assertEquals("2", rs.getString(2));
      assertEquals("xyzz", rs.getString(3));
      assertEquals(4, rs.getInt(4));
      assertEquals(40, rs.getInt(5));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testNullValueEqualityScan() throws Exception {
    String pTSDBTableName = generateUniqueName();
    ensureTableCreated(getUrl(), pTSDBTableName, PTSDB_NAME, null, null, null);
    // Insert all rows at ts
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    PreparedStatement stmt =
      conn.prepareStatement("upsert into " + pTSDBTableName + " VALUES ('', '', ?, 0.5)");
    stmt.setDate(1, D1);
    stmt.execute();
    conn.close();

    // Comparisons against null are always false.
    String query = "SELECT HOST,\"DATE\" FROM " + pTSDBTableName + " WHERE HOST='' AND INST=''";
    url = getUrl();
    conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testVarLengthPKColScan() throws Exception {
    String pTSDBTableName = generateUniqueName();
    ensureTableCreated(getUrl(), pTSDBTableName, PTSDB_NAME, null, null, null);
    String url = getUrl(); // Insert at timestamp 0
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    PreparedStatement stmt =
      conn.prepareStatement("upsert into " + pTSDBTableName + " VALUES (?, 'y', ?, 0.5)");
    stmt.setString(1, "x");
    stmt.setDate(2, D1);
    stmt.execute();
    stmt.setString(1, "xy");
    stmt.execute();
    conn.close();

    String query = "SELECT HOST,\"DATE\" FROM " + pTSDBTableName + " WHERE INST='x' AND HOST='y'";
    url = getUrl();
    conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(D1, rs.getDate(2));
    } finally {
      conn.close();
    }
  }

  @Test
  public void testEscapedQuoteScan() throws Exception {
    String pTSDBTableName = generateUniqueName();
    ensureTableCreated(getUrl(), pTSDBTableName, PTSDB_NAME, null, null, null);
    String url = getUrl(); // Insert at timestamp 0
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    PreparedStatement stmt =
      conn.prepareStatement("upsert into " + pTSDBTableName + " VALUES (?, 'y', ?, 0.5)");
    stmt.setString(1, "x'y");
    stmt.setDate(2, D1);
    stmt.execute();
    stmt.setString(1, "x");
    stmt.execute();
    conn.close();

    String query1 = "SELECT INST,\"DATE\" FROM " + pTSDBTableName + " WHERE INST='x''y'";
    String query2 = "SELECT INST,\"DATE\" FROM " + pTSDBTableName + " WHERE INST='x\\\'y'";
    url = getUrl();
    conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query1);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("x'y", rs.getString(1));
      assertEquals(D1, rs.getDate(2));
      assertFalse(rs.next());

      statement = conn.prepareStatement(query2);
      rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("x'y", rs.getString(1));
      assertEquals(D1, rs.getDate(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  private static void initPTSDBTableValues1(String pTSDBTableName) throws Exception {
    ensureTableCreated(getUrl(), pTSDBTableName, PTSDB_NAME, null, null, null);
    String url = getUrl(); // Insert at timestamp 0
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    PreparedStatement stmt =
      conn.prepareStatement("upsert into " + pTSDBTableName + " VALUES ('x', 'y', ?, 0.5)");
    stmt.setDate(1, D1);
    stmt.execute();
    conn.close();
  }

  @Test
  public void testToStringOnDate() throws Exception {
    String pTSDBTableName = generateUniqueName();
    initPTSDBTableValues1(pTSDBTableName);
    String query = "SELECT HOST,\"DATE\" FROM " + pTSDBTableName + " WHERE INST='x' AND HOST='y'";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(DateUtil.DEFAULT_DATE_FORMATTER.format(D1), rs.getString(2));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  private static void initPTSDBTableValues2(String pTSDB2TableName, Date d) throws Exception {
    ensureTableCreated(getUrl(), pTSDB2TableName, PTSDB2_NAME, null, null, null);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    PreparedStatement stmt = conn
      .prepareStatement("upsert into " + pTSDB2TableName + "(inst,\"DATE\",val2) VALUES (?, ?, ?)");
    stmt.setString(1, "a");
    stmt.setDate(2, d);
    stmt.setDouble(3, 101.3);
    stmt.execute();
    stmt.setString(1, "a");
    stmt.setDate(2, new Date(d.getTime() + 1 * MILLIS_IN_DAY));
    stmt.setDouble(3, 99.7);
    stmt.execute();
    stmt.setString(1, "a");
    stmt.setDate(2, new Date(d.getTime() - 1 * MILLIS_IN_DAY));
    stmt.setDouble(3, 105.3);
    stmt.execute();
    stmt.setString(1, "b");
    stmt.setDate(2, d);
    stmt.setDouble(3, 88.5);
    stmt.execute();
    stmt.setString(1, "b");
    stmt.setDate(2, new Date(d.getTime() + 1 * MILLIS_IN_DAY));
    stmt.setDouble(3, 89.7);
    stmt.execute();
    stmt.setString(1, "b");
    stmt.setDate(2, new Date(d.getTime() - 1 * MILLIS_IN_DAY));
    stmt.setDouble(3, 94.9);
    stmt.execute();
    conn.close();
  }

  @Test
  public void testRoundOnDate() throws Exception {
    String pTSDB2TableName = generateUniqueName();

    Date date = new Date(System.currentTimeMillis());
    initPTSDBTableValues2(pTSDB2TableName, date);

    String query = "SELECT MAX(val2)" + " FROM " + pTSDB2TableName + " WHERE inst='a'"
      + " GROUP BY ROUND(\"DATE\",'day',1)" + " ORDER BY MAX(val2)"; // disambiguate row order
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(99.7, rs.getDouble(1), 1e-6);
      assertTrue(rs.next());
      assertEquals(101.3, rs.getDouble(1), 1e-6);
      assertTrue(rs.next());
      assertEquals(105.3, rs.getDouble(1), 1e-6);
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testOrderBy() throws Exception {
    String pTSDB2TableName = generateUniqueName();
    Date date = new Date(System.currentTimeMillis());
    initPTSDBTableValues2(pTSDB2TableName, date);

    String query = "SELECT inst,MAX(val2),MIN(val2)" + " FROM " + pTSDB2TableName
      + " GROUP BY inst,ROUND(\"DATE\",'day',1)" + " ORDER BY inst,ROUND(\"DATE\",'day',1)";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(105.3, rs.getDouble(2), 1e-6);
      assertEquals(105.3, rs.getDouble(3), 1e-6);
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(101.3, rs.getDouble(2), 1e-6);
      assertEquals(101.3, rs.getDouble(3), 1e-6);
      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));
      assertEquals(99.7, rs.getDouble(2), 1e-6);
      assertEquals(99.7, rs.getDouble(3), 1e-6);
      assertTrue(rs.next());
      assertEquals("b", rs.getString(1));
      assertEquals(94.9, rs.getDouble(2), 1e-6);
      assertEquals(94.9, rs.getDouble(3), 1e-6);
      assertTrue(rs.next());
      assertEquals("b", rs.getString(1));
      assertEquals(88.5, rs.getDouble(2), 1e-6);
      assertEquals(88.5, rs.getDouble(3), 1e-6);
      assertTrue(rs.next());
      assertEquals("b", rs.getString(1));
      assertEquals(89.7, rs.getDouble(2), 1e-6);
      assertEquals(89.7, rs.getDouble(3), 1e-6);
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSelectCount() throws Exception {
    String pTSDB2TableName = generateUniqueName();
    Date date = new Date(System.currentTimeMillis());
    initPTSDBTableValues2(pTSDB2TableName, date);
    String query = "SELECT COUNT(*)" + " FROM " + pTSDB2TableName + " WHERE inst='a'";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(3, rs.getInt(1));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testBatchUpsert() throws Exception {
    String pTSDB2TableName = generateUniqueName();
    Date d = new Date(System.currentTimeMillis());
    ensureTableCreated(getUrl(), pTSDB2TableName, PTSDB2_NAME, null, null, null);
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    String query = "SELECT SUM(val1),SUM(val2),SUM(val3) FROM " + pTSDB2TableName;
    String sql1 = "UPSERT INTO " + pTSDB2TableName + "(inst,\"DATE\",val1) VALUES (?, ?, ?)";
    String sql2 = "UPSERT INTO " + pTSDB2TableName + "(inst,\"DATE\",val2) VALUES (?, ?, ?)";
    String sql3 = "UPSERT INTO " + pTSDB2TableName + "(inst,\"DATE\",val3) VALUES (?, ?, ?)";
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.setAutoCommit(false);
    // conn.setAutoCommit(true);

    {
      // verify precondition: SUM(val{1,2,3}) are null
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertNull(rs.getBigDecimal(1));
      assertNull(rs.getBigDecimal(2));
      assertNull(rs.getBigDecimal(3));
      assertFalse(rs.next());
      statement.close();
    }

    {
      PreparedStatement s = conn.prepareStatement(sql1);
      s.setString(1, "a");
      s.setDate(2, d);
      s.setInt(3, 1);
      assertEquals(1, s.executeUpdate());
      s.close();
    }
    {
      PreparedStatement s = conn.prepareStatement(sql2);
      s.setString(1, "b");
      s.setDate(2, d);
      s.setInt(3, 1);
      assertEquals(1, s.executeUpdate());
      s.close();
    }
    {
      PreparedStatement s = conn.prepareStatement(sql3);
      s.setString(1, "c");
      s.setDate(2, d);
      s.setInt(3, 1);
      assertEquals(1, s.executeUpdate());
      s.close();
    }
    {
      PreparedStatement s = conn.prepareStatement(sql1);
      s.setString(1, "a");
      s.setDate(2, d);
      s.setInt(3, 5);
      assertEquals(1, s.executeUpdate());
      s.close();
    }
    {
      PreparedStatement s = conn.prepareStatement(sql1);
      s.setString(1, "b");
      s.setDate(2, d);
      s.setInt(3, 5);
      assertEquals(1, s.executeUpdate());
      s.close();
    }
    {
      PreparedStatement s = conn.prepareStatement(sql1);
      s.setString(1, "c");
      s.setDate(2, d);
      s.setInt(3, 5);
      assertEquals(1, s.executeUpdate());
      s.close();
    }
    conn.commit();
    conn.close();

    // Query at a time after the upsert to confirm they took place
    conn = DriverManager.getConnection(getUrl(), props);
    {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(15, rs.getDouble(1), 1e-6);
      assertEquals(1, rs.getDouble(2), 1e-6);
      assertEquals(1, rs.getDouble(3), 1e-6);
      assertFalse(rs.next());
      statement.close();
    }
  }

  @Test
  public void testSelectStar() throws Exception {
    String pTSDBTableName = generateUniqueName();
    initPTSDBTableValues1(pTSDBTableName);
    String query = "SELECT * FROM " + pTSDBTableName + " WHERE INST='x' AND HOST='y'";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("x", rs.getString("inst"));
      assertEquals("y", rs.getString("host"));
      assertEquals(D1, rs.getDate("DATE"));
      assertEquals(BigDecimal.valueOf(0.5), rs.getBigDecimal("val"));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testToCharOnDate() throws Exception {
    String pTSDBTableName = generateUniqueName();

    initPTSDBTableValues1(pTSDBTableName);

    String query =
      "SELECT HOST,TO_CHAR(\"DATE\") FROM " + pTSDBTableName + " WHERE INST='x' AND HOST='y'";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(DateUtil.DEFAULT_DATE_FORMATTER.format(D1), rs.getString(2));
    } finally {
      conn.close();
    }
  }

  @Test
  public void testToCharWithFormatOnDate() throws Exception {
    String pTSDBTableName = generateUniqueName();

    initPTSDBTableValues1(pTSDBTableName);
    String format = "HH:mm:ss";
    Format dateFormatter = DateUtil.getDateFormatter(format);
    String query = "SELECT HOST,TO_CHAR(\"DATE\",'" + format + "') FROM " + pTSDBTableName
      + " WHERE INST='x' AND HOST='y'";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(dateFormatter.format(D1), rs.getString(2));
    } finally {
      conn.close();
    }
  }

  @Test
  public void testToDateWithFormatOnDate() throws Exception {
    String pTSDBTableName = generateUniqueName();

    initPTSDBTableValues1(pTSDBTableName);

    String format = "yyyy-MM-dd HH:mm:ss.S";
    Format dateFormatter = DateUtil.getDateFormatter(format);
    String query = "SELECT HOST,TO_CHAR(\"DATE\",'" + format + "') FROM " + pTSDBTableName
      + " WHERE INST='x' AND HOST='y' and \"DATE\"=TO_DATE(?,'" + format + "')";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      statement.setString(1, dateFormatter.format(D1));
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(dateFormatter.format(D1), rs.getString(2));
    } finally {
      conn.close();
    }
  }

  @Test
  public void testMissingPKColumn() throws Exception {
    String pTSDBTableName = generateUniqueName();
    ensureTableCreated(getUrl(), pTSDBTableName, PTSDB_NAME, null, null, null);
    String url = getUrl(); // Insert at timestamp 0
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    Statement stmt = conn.createStatement();
    try {
      stmt.execute(
        "upsert into " + pTSDBTableName + "(INST,HOST,VAL) VALUES ('abc', 'abc-def-ghi', 0.5)");
      fail();
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.CONSTRAINT_VIOLATION.getErrorCode(), e.getErrorCode());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testNoKVColumn() throws Exception {
    String pTSDBTableName = generateUniqueName();

    ensureTableCreated(getUrl(), pTSDBTableName, BTABLE_NAME, null, null, null);
    String url = getUrl(); // Insert at timestamp 0
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    PreparedStatement stmt =
      conn.prepareStatement("upsert into " + pTSDBTableName + " VALUES (?, ?, ?, ?, ?)");
    stmt.setString(1, "abc");
    stmt.setString(2, "123");
    stmt.setString(3, "x");
    stmt.setInt(4, 1);
    stmt.setString(5, "ab");
    // Succeeds since we have an empty KV
    stmt.execute();
  }

  @Test
  public void testTooShortKVColumn() throws Exception {
    String pTSDBTableName = generateUniqueName();
    ensureTableCreated(getUrl(), pTSDBTableName, BTABLE_NAME, null, null, null);
    String url = getUrl(); // Insert at timestamp 0
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    // Insert all rows at ts
    PreparedStatement stmt = conn.prepareStatement(
      "upsert into " + pTSDBTableName + " (" + "    A_STRING, " + "    A_ID," + "    B_STRING,"
        + "    A_INTEGER," + "    C_STRING," + "    E_STRING)" + "VALUES (?, ?, ?, ?, ?, ?)");
    stmt.setString(1, "abc");
    stmt.setString(2, "123");
    stmt.setString(3, "x");
    stmt.setInt(4, 1);
    stmt.setString(5, "ab");
    stmt.setString(6, "01234");

    try {
      stmt.execute();
    } catch (ConstraintViolationException e) {
      fail("Constraint voilation Exception should not be thrown, the characters have to be padded");
    } finally {
      conn.close();
    }
  }

  @Test
  public void testTooShortPKColumn() throws Exception {
    String pTSDBTableName = generateUniqueName();
    ensureTableCreated(getUrl(), pTSDBTableName, BTABLE_NAME, null, null, null);
    String url = getUrl(); // Insert at timestamp 0
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    // Insert all rows at ts
    PreparedStatement stmt = conn.prepareStatement(
      "upsert into " + pTSDBTableName + " (" + "    A_STRING, " + "    A_ID," + "    B_STRING,"
        + "    A_INTEGER," + "    C_STRING," + "    E_STRING)" + "VALUES (?, ?, ?, ?, ?, ?)");
    stmt.setString(1, "abc");
    stmt.setString(2, "12");
    stmt.setString(3, "x");
    stmt.setInt(4, 1);
    stmt.setString(5, "ab");
    stmt.setString(6, "0123456789");

    try {
      stmt.execute();
    } catch (ConstraintViolationException e) {
      fail("Constraint voilation Exception should not be thrown, the characters have to be padded");
    } finally {
      conn.close();
    }
  }

  @Test
  public void testTooLongPKColumn() throws Exception {
    String bTableName = generateUniqueName();
    ensureTableCreated(getUrl(), bTableName, BTABLE_NAME, null, null, null);
    String url = getUrl(); // Insert at timestamp 0
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    // Insert all rows at ts
    PreparedStatement stmt = conn.prepareStatement(
      "upsert into " + bTableName + "(" + "    A_STRING, " + "    A_ID," + "    B_STRING,"
        + "    A_INTEGER," + "    C_STRING," + "    E_STRING)" + "VALUES (?, ?, ?, ?, ?, ?)");
    stmt.setString(1, "abc");
    stmt.setString(2, "123");
    stmt.setString(3, "x");
    stmt.setInt(4, 1);
    stmt.setString(5, "abc");
    stmt.setString(6, "0123456789");

    try {
      stmt.execute();
      fail();
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.DATA_EXCEEDS_MAX_CAPACITY.getErrorCode(), e.getErrorCode());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testTooLongKVColumn() throws Exception {
    String bTableName = generateUniqueName();
    ensureTableCreated(getUrl(), bTableName, BTABLE_NAME, null, null, null);
    String url = getUrl(); // Insert at timestamp 0
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    // Insert all rows at ts
    PreparedStatement stmt = conn.prepareStatement("upsert into " + bTableName + "("
      + "    A_STRING, " + "    A_ID," + "    B_STRING," + "    A_INTEGER," + "    C_STRING,"
      + "    D_STRING," + "    E_STRING)" + "VALUES (?, ?, ?, ?, ?, ?, ?)");
    stmt.setString(1, "abc");
    stmt.setString(2, "123");
    stmt.setString(3, "x");
    stmt.setInt(4, 1);
    stmt.setString(5, "ab");
    stmt.setString(6, "abcd");
    stmt.setString(7, "0123456789");

    try {
      stmt.execute();
      fail();
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.DATA_EXCEEDS_MAX_CAPACITY.getErrorCode(), e.getErrorCode());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testMultiFixedLengthNull() throws Exception {
    String bTableName = generateUniqueName();
    String query =
      "SELECT B_INTEGER,C_INTEGER,COUNT(1) FROM " + bTableName + " GROUP BY B_INTEGER,C_INTEGER";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertTrue(rs.wasNull());
      assertEquals(0, rs.getInt(2));
      assertTrue(rs.wasNull());
      assertEquals(1, rs.getLong(3));

      assertTrue(rs.next());
      assertEquals(10, rs.getInt(1));
      assertEquals(1000, rs.getInt(2));
      assertEquals(2, rs.getLong(3));

      assertTrue(rs.next());
      assertEquals(40, rs.getInt(1));
      assertEquals(0, rs.getInt(2));
      assertTrue(rs.wasNull());
      assertEquals(1, rs.getLong(3));

      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSingleFixedLengthNull() throws Exception {
    String bTableName = generateUniqueName();
    String query = "SELECT C_INTEGER,COUNT(1) FROM " + bTableName + " GROUP BY C_INTEGER";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertTrue(rs.wasNull());
      assertEquals(2, rs.getLong(2));

      assertTrue(rs.next());
      assertEquals(1000, rs.getInt(1));
      assertEquals(2, rs.getLong(2));

      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testMultiMixedTypeGroupBy() throws Exception {
    String bTableName = generateUniqueName();
    String query = "SELECT A_ID, E_STRING, D_STRING, C_INTEGER, COUNT(1) FROM " + bTableName
      + " GROUP BY A_ID, E_STRING, D_STRING, C_INTEGER";
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      PreparedStatement statement = conn.prepareStatement(query);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertEquals("111", rs.getString(1));
      assertEquals("0123456789", rs.getString(2));
      assertEquals(null, rs.getString(3));
      assertEquals(1000, rs.getInt(4));
      assertEquals(1, rs.getInt(5));

      assertTrue(rs.next());
      assertEquals("222", rs.getString(1));
      assertEquals("0123456789", rs.getString(2));
      assertEquals(null, rs.getString(3));
      assertEquals(0, rs.getInt(4));
      assertTrue(rs.wasNull());
      assertEquals(2, rs.getInt(5));

      assertTrue(rs.next());
      assertEquals("222", rs.getString(1));
      assertEquals("0123456789", rs.getString(2));
      assertEquals("efg", rs.getString(3));
      assertEquals(1000, rs.getInt(4));
      assertEquals(1, rs.getInt(5));

      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSubstrFunction() throws Exception {
    String bTableName = generateUniqueName();
    String varcharKeyTestTable = generateUniqueName();
    String query[] = { "SELECT substr('ABC',-1,1) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ABC',-4,1) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ABC',2,4) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ABC',1,1) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ABC',0,1) FROM " + bTableName + " LIMIT 1",
      // Test for multibyte characters support.
      "SELECT substr('ĎďĒ',0,1) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ĎďĒ',0,2) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ĎďĒ',1,1) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ĎďĒ',1,2) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ĎďĒ',2,1) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ĎďĒ',2,2) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('ĎďĒ',-1,1) FROM " + bTableName + " LIMIT 1",
      "SELECT substr('Ďďɚʍ',2,4) FROM " + bTableName + " LIMIT 1",
      "SELECT pk FROM " + varcharKeyTestTable + " WHERE substr(pk, 0, 3)='jkl'", };
    String result[] =
      { "C", null, "BC", "A", "A", "Ď", "Ďď", "Ď", "Ďď", "ď", "ďĒ", "Ē", "ďɚʍ", "jkl   ", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      initVarcharKeyTableValues(null, varcharKeyTestTable);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testRegexReplaceFunction() throws Exception {
    String bTableName = generateUniqueName();
    // NOTE: we need to double escape the "\\" here because conn.prepareStatement would
    // also try to evaluate the escaping. As a result, to represent what normally would be
    // a "\d" in this test, it would become "\\\\d".
    String query[] = { "SELECT regexp_replace('', '') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('', 'abc', 'def') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('123abcABC', '[a-z]+') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('123-abc-ABC', '-[a-zA-Z-]+') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('abcABC123', '\\\\d+', '') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('abcABC123', '\\\\D+', '') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('abc', 'abc', 'def') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('abc123ABC', '\\\\d+', 'def') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('abc123ABC', '[0-9]+', '#') FROM " + bTableName + " LIMIT 1",
      "SELECT CASE WHEN regexp_replace('abcABC123', '[a-zA-Z]+') = '123' THEN '1' ELSE '2' END FROM "
        + bTableName + " LIMIT 1",
      "SELECT A_STRING FROM " + bTableName
        + " WHERE A_ID = regexp_replace('abcABC111', '[a-zA-Z]+') LIMIT 1", // 111
      // Test for multibyte characters support.
      "SELECT regexp_replace('Ďď Ēĕ ĜĞ ϗϘϛϢ', '[a-zA-Z]+') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('Ďď Ēĕ ĜĞ ϗϘϛϢ', '[Ď-ě]+', '#') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('Ďď Ēĕ ĜĞ ϗϘϛϢ', '.+', 'replacement') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_replace('Ďď Ēĕ ĜĞ ϗϘϛϢ', 'Ďď', 'DD') FROM " + bTableName + " LIMIT 1", };
    String result[] =
      { null, null, "123ABC", "123", "abcABC", "123", "def", "abcdefABC", "abc#ABC", "1", "abc", // the
                                                                                                 // first
                                                                                                 // column
        "Ďď Ēĕ ĜĞ ϗϘϛϢ", "# # ĜĞ ϗϘϛϢ", "replacement", "DD Ēĕ ĜĞ ϗϘϛϢ", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testRegexpSubstrFunction() throws Exception {
    String bTableName = generateUniqueName();
    String query[] = { "SELECT regexp_substr('', '', 0) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('', '', 1) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('', 'abc', 0) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('abc', '', 0) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('123', '123', 3) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('123', '123', -4) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('123ABC', '[a-z]+', 0) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('123ABC', '[0-9]+', 4) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('123ABCabc', '\\\\d+', 0) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('123ABCabc', '\\\\D+', 0) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('123ABCabc', '\\\\D+', 4) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('123ABCabc', '\\\\D+', 7) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('na11-app5-26-sjl', '[^-]+', 0) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('na11-app5-26-sjl', '[^-]+') FROM " + bTableName + " LIMIT 1",
      // Test for multibyte characters support.
      "SELECT regexp_substr('ĎďĒĕĜĞ', '.+') FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('ĎďĒĕĜĞ', '.+', 3) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('ĎďĒĕĜĞ', '[a-zA-Z]+', 0) FROM " + bTableName + " LIMIT 1",
      "SELECT regexp_substr('ĎďĒĕĜĞ', '[Ď-ě]+', 3) FROM " + bTableName + " LIMIT 1", };
    String result[] = { null, null, null, null, null, null, null, null, "123", "ABCabc", "ABCabc",
      "abc", "na11", "na11", "ĎďĒĕĜĞ", "ĒĕĜĞ", null, "Ēĕ", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testRegexpSubstrFunction2() throws Exception {
    String tTableName = generateUniqueName();
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    String ddl = "create table " + tTableName + " (k INTEGER NOT NULL PRIMARY KEY, name VARCHAR)";
    conn.createStatement().execute(ddl);
    conn.close();

    String dml = "upsert into " + tTableName + " values(?,?)";
    conn = DriverManager.getConnection(url, props);
    PreparedStatement stmt = conn.prepareStatement(dml);
    String[] values = new String[] { "satax", "jruls", "hrjcu", "yqtrv", "jjcvw" };
    for (int i = 0; i < values.length; i++) {
      stmt.setInt(1, i + 1);
      stmt.setString(2, values[i]);
      stmt.execute();
    }
    conn.commit();
    conn.close();

    // This matches what Oracle returns for regexp_substr, even through
    // it seems oke for "satax", it should return null.
    String query = "select regexp_substr(name,'[^s]+',1) from " + tTableName + " limit 5";
    conn = DriverManager.getConnection(url, props);
    ResultSet rs = conn.createStatement().executeQuery(query);
    int count = 0;
    String[] results = new String[] { "atax", "jrul", "hrjcu", "yqtrv", "jjcvw" };
    while (rs.next()) {
      assertEquals(results[count], rs.getString(1));
      count++;
    }
  }

  @Test
  public void testLikeConstant() throws Exception {
    String bTableName = generateUniqueName();
    String query[] =
      { "SELECT CASE WHEN 'ABC' LIKE '' THEN '1' ELSE '2' END FROM " + bTableName + " LIMIT 1",
        "SELECT CASE WHEN 'ABC' LIKE 'A_' THEN '1' ELSE '2' END FROM " + bTableName + " LIMIT 1",
        "SELECT CASE WHEN 'ABC' LIKE 'A__' THEN '1' ELSE '2' END FROM " + bTableName + " LIMIT 1",
        "SELECT CASE WHEN 'AB_C' LIKE 'AB\\_C' THEN '1' ELSE '2' END FROM " + bTableName
          + " LIMIT 1",
        "SELECT CASE WHEN 'ABC%DE' LIKE 'ABC\\%D%' THEN '1' ELSE '2' END FROM " + bTableName
          + " LIMIT 1", };
    String result[] = { "2", "2", "1", "1", "1", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(query[i], result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testInListConstant() throws Exception {
    String bTableName = generateUniqueName();

    String query[] =
      { "SELECT CASE WHEN 'a' IN (null,'a') THEN '1' ELSE '2' END FROM " + bTableName + " LIMIT 1",
        "SELECT CASE WHEN NOT 'a' IN (null,'b') THEN '1' ELSE '2' END FROM " + bTableName
          + " LIMIT 1",
        "SELECT CASE WHEN 'a' IN (null,'b') THEN '1' ELSE '2' END FROM " + bTableName + " LIMIT 1",
        "SELECT CASE WHEN NOT 'a' IN ('c','b') THEN '1' ELSE '2' END FROM " + bTableName
          + " LIMIT 1",
        "SELECT CASE WHEN 1 IN ('foo',2,1) THEN '1' ELSE '2' END FROM " + bTableName + " LIMIT 1",
        "SELECT CASE WHEN NOT null IN ('c','b') THEN '1' ELSE '2' END FROM " + bTableName
          + " LIMIT 1",
        "SELECT CASE WHEN NOT null IN (null,'c','b') THEN '1' ELSE '2' END FROM " + bTableName
          + " LIMIT 1",
        "SELECT CASE WHEN null IN (null,'c','b') THEN '1' ELSE '2' END FROM " + bTableName
          + " LIMIT 1",
        "SELECT CASE WHEN 'a' IN (null,1) THEN '1' ELSE '2' END FROM " + bTableName + " LIMIT 1", };
    String result[] = { "1", "1", "2", "1", "1", "2", "2", "2", "2" };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(query[i], result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testLikeOnColumn() throws Exception {
    String pTSDBTableName = generateUniqueName();
    ensureTableCreated(getUrl(), pTSDBTableName, PTSDB_NAME, null, null, null);
    // Insert all rows at ts
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    PreparedStatement stmt =
      conn.prepareStatement("upsert into " + pTSDBTableName + " VALUES (?, ?, ?, 0.5)");
    stmt.setDate(3, D1);

    stmt.setString(1, "a");
    stmt.setString(2, "a");
    stmt.execute();

    stmt.setString(1, "x");
    stmt.setString(2, "a");
    stmt.execute();

    stmt.setString(1, "xy");
    stmt.setString(2, "b");
    stmt.execute();

    stmt.setString(1, "xyz");
    stmt.setString(2, "c");
    stmt.execute();

    stmt.setString(1, "xyza");
    stmt.setString(2, "d");
    stmt.execute();

    stmt.setString(1, "xyzab");
    stmt.setString(2, "e");
    stmt.execute();

    stmt.setString(1, "z");
    stmt.setString(2, "e");
    stmt.execute();

    conn.commit();
    conn.close();

    url = getUrl();
    conn = DriverManager.getConnection(url, props);
    PreparedStatement statement;
    ResultSet rs;
    try {
      // Test 1
      statement =
        conn.prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE INST LIKE 'x%'");
      rs = statement.executeQuery();

      assertTrue(rs.next());
      assertEquals("x", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xy", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyz", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyza", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyzab", rs.getString(1));

      assertFalse(rs.next());

      // Test 2
      statement =
        conn.prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE INST LIKE 'xy_a%'");
      rs = statement.executeQuery();

      assertTrue(rs.next());
      assertEquals("xyza", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyzab", rs.getString(1));

      assertFalse(rs.next());

      // Test 3
      statement = conn
        .prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE INST NOT LIKE 'xy_a%'");
      rs = statement.executeQuery();

      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("x", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xy", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyz", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("z", rs.getString(1));

      assertFalse(rs.next());

      // Test 4
      statement =
        conn.prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE 'xzabc' LIKE 'xy_a%'");
      rs = statement.executeQuery();
      assertFalse(rs.next());

      // Test 5
      statement = conn
        .prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE 'abcdef' LIKE '%bCd%'");
      rs = statement.executeQuery();
      assertFalse(rs.next());

    } finally {
      conn.close();
    }
  }

  @Test
  public void testILikeOnColumn() throws Exception {
    String pTSDBTableName = generateUniqueName();
    ensureTableCreated(getUrl(), pTSDBTableName, PTSDB_NAME, null, null, null);
    // Insert all rows at ts
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    PreparedStatement stmt = conn.prepareStatement("upsert into " + pTSDBTableName
      + "(INST, HOST, \"DATE\", VAL, PATTERN VARCHAR) VALUES (?, ?, ?, 0.5, 'x_Z%')");
    stmt.setDate(3, D1);

    stmt.setString(1, "a");
    stmt.setString(2, "a");
    stmt.execute();

    stmt.setString(1, "x");
    stmt.setString(2, "a");
    stmt.execute();

    stmt.setString(1, "xy");
    stmt.setString(2, "b");
    stmt.execute();

    stmt.setString(1, "xyz");
    stmt.setString(2, "c");
    stmt.execute();

    stmt.setString(1, "xyza");
    stmt.setString(2, "d");
    stmt.execute();

    stmt.setString(1, "xyzab");
    stmt.setString(2, "e");
    stmt.execute();

    stmt.setString(1, "z");
    stmt.setString(2, "e");
    stmt.execute();

    conn.commit();
    conn.close();

    url = getUrl();
    conn = DriverManager.getConnection(url, props);
    PreparedStatement statement;
    ResultSet rs;
    try {
      // Test 1
      statement =
        conn.prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE INST ILIKE 'x%'");
      rs = statement.executeQuery();

      assertTrue(rs.next());
      assertEquals("x", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xy", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyz", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyza", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyzab", rs.getString(1));

      assertFalse(rs.next());

      // Test 2
      statement =
        conn.prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE INST ILIKE 'xy_a%'");
      rs = statement.executeQuery();

      assertTrue(rs.next());
      assertEquals("xyza", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyzab", rs.getString(1));

      assertFalse(rs.next());

      // Test 3
      statement = conn
        .prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE INST NOT ILIKE 'xy_a%'");
      rs = statement.executeQuery();

      assertTrue(rs.next());
      assertEquals("a", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("x", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xy", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyz", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("z", rs.getString(1));

      assertFalse(rs.next());

      // Test 4
      statement = conn
        .prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE 'xzabc' ILIKE 'xy_a%'");
      rs = statement.executeQuery();
      assertFalse(rs.next());

      // Test 5
      statement = conn
        .prepareStatement("SELECT INST FROM " + pTSDBTableName + " WHERE 'abcdef' ILIKE '%bCd%'");
      rs = statement.executeQuery();
      assertTrue(rs.next());

      // Test 5
      statement = conn.prepareStatement(
        "SELECT INST FROM " + pTSDBTableName + "(PATTERN VARCHAR) WHERE INST ILIKE PATTERN");
      rs = statement.executeQuery();

      assertTrue(rs.next());
      assertEquals("xyz", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyza", rs.getString(1));

      assertTrue(rs.next());
      assertEquals("xyzab", rs.getString(1));

      assertFalse(rs.next());

    } finally {
      conn.close();
    }
  }

  @Test
  public void testIsNullInPK() throws Exception {
    String pTSDBTableName = generateUniqueName();
    ensureTableCreated(getUrl(), pTSDBTableName, PTSDB_NAME, null, null, null);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    conn.setAutoCommit(true);
    PreparedStatement stmt =
      conn.prepareStatement("upsert into " + pTSDBTableName + " VALUES ('', '', ?, 0.5)");
    stmt.setDate(1, D1);
    stmt.execute();
    conn.close();

    String query = "SELECT HOST,INST,\"DATE\" FROM " + pTSDBTableName
      + " WHERE HOST IS NULL AND INST IS NULL AND \"DATE\"=?";
    url = getUrl();
    conn = DriverManager.getConnection(url, props);
    try {
      PreparedStatement statement = conn.prepareStatement(query);
      statement.setDate(1, D1);
      ResultSet rs = statement.executeQuery();
      assertTrue(rs.next());
      assertNull(rs.getString(1));
      assertNull(rs.getString(2));
      assertEquals(D1, rs.getDate(3));
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testLengthFunction() throws Exception {
    String bTableName = generateUniqueName();
    String query[] = { "SELECT length('') FROM " + bTableName + " LIMIT 1",
      "SELECT length(' ') FROM " + bTableName + " LIMIT 1",
      "SELECT length('1') FROM " + bTableName + " LIMIT 1",
      "SELECT length('1234') FROM " + bTableName + " LIMIT 1",
      "SELECT length('ɚɦɰɸ') FROM " + bTableName + " LIMIT 1",
      "SELECT length('ǢǛǟƈ') FROM " + bTableName + " LIMIT 1",
      "SELECT length('This is a test!') FROM " + bTableName + " LIMIT 1",
      "SELECT A_STRING FROM " + bTableName + " WHERE length(A_STRING)=3", };
    String result[] = { null, "1", "1", "4", "4", "4", "15", "abc", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(query[i], result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testUpperFunction() throws Exception {
    String bTableName = generateUniqueName();
    String query[] = { "SELECT upper('abc') FROM " + bTableName + " LIMIT 1",
      "SELECT upper('Abc') FROM " + bTableName + " LIMIT 1",
      "SELECT upper('ABC') FROM " + bTableName + " LIMIT 1",
      "SELECT upper('ĎďĒ') FROM " + bTableName + " LIMIT 1",
      "SELECT upper('ß') FROM " + bTableName + " LIMIT 1", };
    String result[] = { "ABC", "ABC", "ABC", "ĎĎĒ", "SS", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(query[i], result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testLowerFunction() throws Exception {
    String bTableName = generateUniqueName();
    String query[] = { "SELECT lower('abc') FROM " + bTableName + " LIMIT 1",
      "SELECT lower('Abc') FROM " + bTableName + " LIMIT 1",
      "SELECT lower('ABC') FROM " + bTableName + " LIMIT 1",
      "SELECT lower('ĎďĒ') FROM " + bTableName + " LIMIT 1",
      "SELECT lower('ß') FROM " + bTableName + " LIMIT 1",
      "SELECT lower('SS') FROM " + bTableName + " LIMIT 1", };
    String result[] = { "abc", "abc", "abc", "ďďē", "ß", "ss", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(query[i], result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testRTrimFunction() throws Exception {
    String bTableName = generateUniqueName();
    String varcharKeyTestTable = generateUniqueName();
    String query[] = { "SELECT rtrim('') FROM " + bTableName + " LIMIT 1",
      "SELECT rtrim(' ') FROM " + bTableName + " LIMIT 1",
      "SELECT rtrim('   ') FROM " + bTableName + " LIMIT 1",
      "SELECT rtrim('abc') FROM " + bTableName + " LIMIT 1",
      "SELECT rtrim('abc   ') FROM " + bTableName + " LIMIT 1",
      "SELECT rtrim('abc   def') FROM " + bTableName + " LIMIT 1",
      "SELECT rtrim('abc   def   ') FROM " + bTableName + " LIMIT 1",
      "SELECT rtrim('ĎďĒ   ') FROM " + bTableName + " LIMIT 1",
      "SELECT pk FROM " + varcharKeyTestTable + " WHERE rtrim(pk)='jkl' LIMIT 1", };
    String result[] =
      { null, null, null, "abc", "abc", "abc   def", "abc   def", "ĎďĒ", "jkl   ", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      initVarcharKeyTableValues(null, varcharKeyTestTable);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(query[i], result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testLTrimFunction() throws Exception {
    String bTableName = generateUniqueName();
    String varcharKeyTestTable = generateUniqueName();
    String query[] = { "SELECT ltrim('') FROM " + bTableName + " LIMIT 1",
      "SELECT ltrim(' ') FROM " + bTableName + " LIMIT 1",
      "SELECT ltrim('   ') FROM " + bTableName + " LIMIT 1",
      "SELECT ltrim('abc') FROM " + bTableName + " LIMIT 1",
      "SELECT ltrim('   abc') FROM " + bTableName + " LIMIT 1",
      "SELECT ltrim('abc   def') FROM " + bTableName + " LIMIT 1",
      "SELECT ltrim('   abc   def') FROM " + bTableName + " LIMIT 1",
      "SELECT ltrim('   ĎďĒ') FROM " + bTableName + " LIMIT 1",
      "SELECT pk FROM " + varcharKeyTestTable + " WHERE ltrim(pk)='def' LIMIT 1", };
    String result[] =
      { null, null, null, "abc", "abc", "abc   def", "abc   def", "ĎďĒ", "   def", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      initVarcharKeyTableValues(null, varcharKeyTestTable);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(query[i], result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }

  @Test
  public void testSubstrFunctionOnRowKeyInWhere() throws Exception {
    String substrTestTableName = generateUniqueName();
    String url = getUrl();
    Connection conn = DriverManager.getConnection(url);
    conn.createStatement().execute("CREATE TABLE " + substrTestTableName
      + " (s1 varchar not null, s2 varchar not null constraint pk primary key(s1,s2))");
    conn.close();

    url = getUrl();
    conn = DriverManager.getConnection(url);
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abc','a')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcd','b')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abce','c')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcde','d')");
    conn.commit();
    conn.close();

    url = getUrl();
    conn = DriverManager.getConnection(url);
    ResultSet rs = conn.createStatement()
      .executeQuery("SELECT s1 from " + substrTestTableName + " where substr(s1,1,4) = 'abcd'");
    assertTrue(rs.next());
    assertEquals("abcd", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("abcde", rs.getString(1));
    assertFalse(rs.next());
  }

  @Test
  public void testRTrimFunctionOnRowKeyInWhere() throws Exception {
    String substrTestTableName = generateUniqueName();
    String url = getUrl();
    Connection conn = DriverManager.getConnection(url);
    conn.createStatement().execute("CREATE TABLE " + substrTestTableName
      + " (s1 varchar not null, s2 varchar not null constraint pk primary key(s1,s2))");
    conn.close();

    url = getUrl();
    conn = DriverManager.getConnection(url);
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abc','a')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcd','b')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcd ','c')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcd  ','c')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcd  a','c')"); // Need
                                                                                                     // TRAVERSE_AND_LEAVE
                                                                                                     // for
                                                                                                     // cases
                                                                                                     // like
                                                                                                     // this
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcde','d')");
    conn.commit();
    conn.close();

    url = getUrl();
    conn = DriverManager.getConnection(url);
    ResultSet rs = conn.createStatement()
      .executeQuery("SELECT s1 from " + substrTestTableName + " where rtrim(s1) = 'abcd'");
    assertTrue(rs.next());
    assertEquals("abcd", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("abcd ", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("abcd  ", rs.getString(1));
    assertFalse(rs.next());
  }

  @Test
  public void testLikeFunctionOnRowKeyInWhere() throws Exception {
    String substrTestTableName = generateUniqueName();
    String url = getUrl();
    Connection conn = DriverManager.getConnection(url);
    conn.createStatement().execute("CREATE TABLE " + substrTestTableName
      + " (s1 varchar not null, s2 varchar not null constraint pk primary key(s1,s2))");
    conn.close();

    url = getUrl();
    conn = DriverManager.getConnection(url);
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abc','a')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcd','b')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcd-','c')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abcd-1','c')");
    conn.createStatement().execute("UPSERT INTO " + substrTestTableName + " VALUES('abce','d')");
    conn.commit();
    conn.close();

    url = getUrl();
    conn = DriverManager.getConnection(url);
    ResultSet rs = conn.createStatement()
      .executeQuery("SELECT s1 from " + substrTestTableName + " where s1 like 'abcd%1'");
    assertTrue(rs.next());
    assertEquals("abcd-1", rs.getString(1));
    assertFalse(rs.next());
  }

  @Test
  public void testTrimFunction() throws Exception {
    String bTableName = generateUniqueName();
    String varcharKeyTestTable = generateUniqueName();
    String query[] = { "SELECT trim('') FROM " + bTableName + " LIMIT 1",
      "SELECT trim(' ') FROM " + bTableName + " LIMIT 1",
      "SELECT trim('   ') FROM " + bTableName + " LIMIT 1",
      "SELECT trim('abc') FROM " + bTableName + " LIMIT 1",
      "SELECT trim('   abc') FROM " + bTableName + " LIMIT 1",
      "SELECT trim('abc   ') FROM " + bTableName + " LIMIT 1",
      "SELECT trim('abc   def') FROM " + bTableName + " LIMIT 1",
      "SELECT trim('   abc   def') FROM " + bTableName + " LIMIT 1",
      "SELECT trim('abc   def   ') FROM " + bTableName + " LIMIT 1",
      "SELECT trim('   abc   def   ') FROM " + bTableName + " LIMIT 1",
      "SELECT trim('   ĎďĒ   ') FROM " + bTableName + " LIMIT 1",
      "SELECT pk FROM " + varcharKeyTestTable + " WHERE trim(pk)='ghi'", };
    String result[] = { null, null, null, "abc", "abc", "abc", "abc   def", "abc   def",
      "abc   def", "abc   def", "ĎďĒ", "   ghi   ", };
    assertEquals(query.length, result.length);
    String url = getUrl();
    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
    Connection conn = DriverManager.getConnection(url, props);
    try {
      initBTableValues(null, bTableName);
      initVarcharKeyTableValues(null, varcharKeyTestTable);
      for (int i = 0; i < query.length; i++) {
        PreparedStatement statement = conn.prepareStatement(query[i]);
        ResultSet rs = statement.executeQuery();
        assertTrue(rs.next());
        assertEquals(query[i], result[i], rs.getString(1));
        assertFalse(rs.next());
      }
    } finally {
      conn.close();
    }
  }
}
