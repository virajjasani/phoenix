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

import static org.apache.phoenix.coprocessorclient.BaseScannerRegionObserverConstants.PHOENIX_MAX_LOOKBACK_AGE_CONF_KEY;
import static org.apache.phoenix.mapreduce.index.IndexUpgradeTool.ROLLBACK_OP;
import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeepDeletedCells;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.mapreduce.index.IndexUpgradeTool;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.query.KeyRange;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.ColumnAlreadyExistsException;
import org.apache.phoenix.schema.LiteralTTLExpression;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.ImmutableStorageScheme;
import org.apache.phoenix.schema.PTable.QualifierEncodingScheme;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.SchemaNotFoundException;
import org.apache.phoenix.schema.TableAlreadyExistsException;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.TestUtil;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.phoenix.thirdparty.com.google.common.collect.Maps;

@Category(NeedsOwnMiniClusterTest.class)
public class CreateTableIT extends ParallelStatsDisabledIT {
  @BeforeClass
  public static synchronized void doSetup() throws Exception {
    Map<String, String> props = Maps.newHashMapWithExpectedSize(1);
    props.put(PHOENIX_MAX_LOOKBACK_AGE_CONF_KEY, Integer.toString(60 * 60)); // An hour
    props.put(QueryServices.USE_STATS_FOR_PARALLELIZATION, Boolean.toString(false));
    setUpTestDriver(new ReadOnlyProps(props.entrySet().iterator()));
  }

  @Test
  public void testStartKeyStopKey() throws SQLException {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    conn.createStatement().execute(
      "CREATE TABLE " + tableName + " (pk char(2) not null primary key) SPLIT ON ('EA','EZ')");
    conn.close();

    String query = "select count(*) from  " + tableName + "  where pk >= 'EA' and pk < 'EZ'";
    conn = DriverManager.getConnection(getUrl(), props);
    Statement statement = conn.createStatement();
    statement.execute(query);
    PhoenixStatement pstatement = statement.unwrap(PhoenixStatement.class);
    List<KeyRange> splits = pstatement.getQueryPlan().getSplits();
    assertTrue(splits.size() > 0);
  }

  @Test
  public void testSplitsWithFile() throws Exception {
    File splitFile = new File("splitFile.txt");
    try {
      try (BufferedWriter writer = new BufferedWriter(new FileWriter(splitFile))) {
        writer.write("EA");
        writer.newLine();
        writer.write("EZ");
      }
      Properties props = new Properties();
      Connection conn = DriverManager.getConnection(getUrl(), props);
      String tableName = generateUniqueName();
      conn.createStatement().execute("CREATE TABLE " + tableName
        + " (pk char(2) not null primary key) SPLITS_FILE='splitFile.txt'");
      conn.close();
      String query = "select * from  " + tableName;
      conn = DriverManager.getConnection(getUrl(), props);
      Statement statement = conn.createStatement();
      statement.execute(query);
      PhoenixStatement pstatement = statement.unwrap(PhoenixStatement.class);
      List<KeyRange> splits = pstatement.getQueryPlan().getSplits();
      // There will be 3 region splits: '' - EA, EA - EZ, EZ - ''
      assertEquals(3, splits.size());
    } finally {
      // Delete split file.
      splitFile.delete();
    }
  }

  /**
   * Pass the absolute path of the splits file while creating the table.
   */
  @Test
  public void testSplitsWithAbsoluteFileName() throws Exception {
    File splitFile = new File("splitFile.txt");
    try {
      try (BufferedWriter writer = new BufferedWriter(new FileWriter(splitFile))) {
        writer.write("EA");
        writer.newLine();
        writer.write("EZ");
      }
      Properties props = new Properties();
      Connection conn = DriverManager.getConnection(getUrl(), props);
      String tableName = generateUniqueName();
      String createTableSql = "CREATE TABLE " + tableName
        + " (pk char(2) not null primary key) SPLITS_FILE='" + splitFile.getAbsolutePath() + "'";
      conn.createStatement().execute(createTableSql);
      conn.close();
      String query = "select * from  " + tableName;
      conn = DriverManager.getConnection(getUrl(), props);
      Statement statement = conn.createStatement();
      statement.execute(query);
      PhoenixStatement pstatement = statement.unwrap(PhoenixStatement.class);
      List<KeyRange> splits = pstatement.getQueryPlan().getSplits();
      // There will be 3 region splits: '' - EA, EA - EZ, EZ - ''
      assertEquals(3, splits.size());
    } finally {
      // Delete split file.
      splitFile.delete();
    }
  }

  /**
   * Test create table fails with an invalid file name.
   */
  @Test
  public void testSplitsWithBadFileName() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    try {
      conn.createStatement().execute("CREATE TABLE " + tableName
        + " (pk char(2) not null primary key) SPLITS_FILE='bad-split-file.txt'");
      Assert.fail("Shouldn't come here");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.SPLIT_FILE_DONT_EXIST.getErrorCode(), e.getErrorCode());
    } finally {
      conn.close();
    }
  }

  /**
   * Test create table fails when both split file and split points are provided.
   */
  @Test
  public void testSplitsWithBothSplitPointsAndSplitFileProvided() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    try {
      conn.createStatement()
        .execute("CREATE TABLE " + tableName
          + " (pk char(2) not null primary key) SPLITS_FILE='bad-split-file.txt'"
          + " SPLIT ON ('EA','EZ')");
      Assert.fail("Shouldn't come here");
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.SPLITS_AND_SPLIT_FILE_EXISTS.getErrorCode(), e.getErrorCode());
    } finally {
      conn.close();
    }
  }

  @Test
  public void testCreateAlterTableWithDuplicateColumn() throws Exception {
    Properties props = new Properties();
    int failureCount = 0;
    int expectedExecCount = 0;
    String tableName = generateUniqueName();
    String viewName = generateUniqueName();
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      try {
        // Case 1 - Adding a column as "Default_CF.Column" in
        // CREATE TABLE query where "Column" is same as single PK Column
        conn.createStatement().execute(
          String.format("CREATE TABLE %s" + " (name VARCHAR NOT NULL PRIMARY KEY, city VARCHAR,"
            + " name1 VARCHAR, name VARCHAR)", tableName));
        fail("Should have failed with ColumnAlreadyExistsException");
      } catch (ColumnAlreadyExistsException e) {
        // expected
        failureCount++;
      }
      try {
        // Case 2 - Adding a column as "Default_CF.Column" in CREATE
        // TABLE query where "Default_CF.Column" already exists as non-Pk column
        conn.createStatement().execute(
          String.format("CREATE TABLE %s" + " (name VARCHAR NOT NULL PRIMARY KEY, city VARCHAR,"
            + " name1 VARCHAR, name1 VARCHAR)", tableName));
        fail("Should have failed with ColumnAlreadyExistsException");
      } catch (ColumnAlreadyExistsException e) {
        // expected
        failureCount++;
      }
      try {
        // Case 3 - Adding a column as "Default_CF.Column" in CREATE
        // TABLE query where "Column" is same as single PK Column.
        // The only diff from Case 1 is that both PK Column and
        // "Default_CF.Column" are of different DataType
        conn.createStatement().execute(
          String.format("CREATE TABLE %s" + " (key1 VARCHAR NOT NULL PRIMARY KEY, city VARCHAR,"
            + " name1 VARCHAR, key1 INTEGER)", tableName));
        fail("Should have failed with ColumnAlreadyExistsException");
      } catch (ColumnAlreadyExistsException e) {
        // expected
        failureCount++;
      }
      try {
        // Case 4 - Adding a column as "Default_CF.Column" in
        // CREATE TABLE query where "Column" is same as one of the
        // PK Column from Composite PK Columns
        conn.createStatement()
          .execute(String.format(
            "CREATE TABLE %s" + " (name VARCHAR NOT NULL, name1 VARCHAR NOT NULL,"
              + " name2 VARCHAR, name VARCHAR" + " CONSTRAINT PK PRIMARY KEY(name, name1))",
            tableName));
        fail("Should have failed with ColumnAlreadyExistsException");
      } catch (ColumnAlreadyExistsException e) {
        // expected
        failureCount++;
      }
      try {
        // Case 5 - Adding a column as "Default_CF.Column" in
        // CREATE VIEW query where "Column" is same as one of the
        // PK Column from Composite PK Columns derived from parent table
        conn.createStatement()
          .execute(String.format(
            "CREATE TABLE %s" + " (name VARCHAR NOT NULL, name1 VARCHAR NOT NULL,"
              + " name2 VARCHAR, name3 VARCHAR" + " CONSTRAINT PK PRIMARY KEY(name, name1))",
            tableName));
        expectedExecCount++;
        conn.createStatement().execute(String
          .format("CREATE VIEW %s" + " (name1 CHAR(5)) AS SELECT * FROM %s", viewName, tableName));
        fail("Should have failed with ColumnAlreadyExistsException");
      } catch (ColumnAlreadyExistsException e) {
        // expected
        failureCount++;
      }
      try {
        // Case 6 - Adding a column as "Default_CF.Column" in
        // ALTER TABLE query where "Column" is same as one of the
        // PK Column from Composite PK Columns
        conn.createStatement().execute(String.format("DROP TABLE %s", tableName));
        conn.createStatement()
          .execute(String.format(
            "CREATE TABLE %s" + " (name VARCHAR NOT NULL, name1 VARCHAR NOT NULL,"
              + " name2 VARCHAR, name3 VARCHAR" + " CONSTRAINT PK PRIMARY KEY(name, name1))",
            tableName));
        expectedExecCount++;
        conn.createStatement()
          .execute(String.format("ALTER TABLE %s ADD name1 INTEGER", tableName));
        fail("Should have failed with ColumnAlreadyExistsException");
      } catch (ColumnAlreadyExistsException e) {
        // expected
        failureCount++;
      }
      try {
        // Case 7 - Adding a column as "Default_CF.Column" in
        // ALTER TABLE query where "Column" is same as single PK Column
        conn.createStatement().execute(String.format("DROP TABLE %s", tableName));
        conn.createStatement().execute(
          String.format("CREATE TABLE %s" + " (name VARCHAR NOT NULL PRIMARY KEY, city VARCHAR,"
            + " name1 VARCHAR, name2 VARCHAR)", tableName));
        expectedExecCount++;
        conn.createStatement().execute(String.format("ALTER TABLE %s ADD name VARCHAR", tableName));
        fail("Should have failed with ColumnAlreadyExistsException");
      } catch (ColumnAlreadyExistsException e) {
        // expected
        failureCount++;
      }
      assertEquals(7, failureCount);
      assertEquals(3, expectedExecCount);

      conn.createStatement().execute(String.format("DROP TABLE %s", tableName));
      // Case 8 - Adding a column as "Non_Default_CF.Column" in
      // CREATE TABLE query where "Column" is same as single PK Column
      // Hence, we allow creating such column
      conn.createStatement().execute(
        String.format("CREATE TABLE %s" + " (name VARCHAR NOT NULL PRIMARY KEY, city VARCHAR,"
          + " name1 VARCHAR, a.name VARCHAR)", tableName));
      conn.createStatement().execute(String.format("DROP TABLE %s", tableName));
      // Case 9 - Adding a column as "Non_Default_CF.Column" in
      // ALTER TABLE query where "Column" is same as single PK Column
      // Hence, we allow creating such column
      conn.createStatement().execute(
        String.format("CREATE TABLE %s" + " (name VARCHAR NOT NULL PRIMARY KEY, city VARCHAR,"
          + " name1 VARCHAR, name2 VARCHAR)", tableName));
      conn.createStatement().execute(String.format("ALTER TABLE %s ADD a.name VARCHAR", tableName));
    }
  }

  @Test
  public void testCreateTable() throws Exception {
    String schemaName = "TEST";
    String tableName = schemaName + generateUniqueName();
    Properties props = new Properties();

    String ddl = "CREATE TABLE " + tableName + "(                data.addtime VARCHAR ,\n"
      + "                data.dir VARCHAR ,\n" + "                data.end_time VARCHAR ,\n"
      + "                data.file VARCHAR ,\n" + "                data.fk_log VARCHAR ,\n"
      + "                data.host VARCHAR ,\n" + "                data.r VARCHAR ,\n"
      + "                data.size VARCHAR ,\n" + "                data.start_time VARCHAR ,\n"
      + "                data.stat_date DATE ,\n" + "                data.stat_hour VARCHAR ,\n"
      + "                data.stat_minute VARCHAR ,\n" + "                data.state VARCHAR ,\n"
      + "                data.title VARCHAR ,\n" + "                data.\"user\" VARCHAR ,\n"
      + "                data.inrow VARCHAR ,\n" + "                data.jobid VARCHAR ,\n"
      + "                data.jobtype VARCHAR ,\n" + "                data.level VARCHAR ,\n"
      + "                data.msg VARCHAR ,\n" + "                data.outrow VARCHAR ,\n"
      + "                data.pass_time VARCHAR ,\n" + "                data.type VARCHAR ,\n"
      + "                id INTEGER not null primary key desc\n" + "                ) ";
    try (Connection conn = DriverManager.getConnection(getUrl(), props);) {
      conn.createStatement().execute(ddl);
    }
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();
    assertNotNull(admin.getDescriptor(TableName.valueOf(tableName)));
    ColumnFamilyDescriptor[] columnFamilies =
      admin.getDescriptor(TableName.valueOf(tableName)).getColumnFamilies();
    assertEquals(BloomType.ROW, columnFamilies[0].getBloomFilterType());

    try (Connection conn = DriverManager.getConnection(getUrl(), props);) {
      conn.createStatement().execute(ddl);
      fail();
    } catch (TableAlreadyExistsException e) {
      // expected
    }
    try (Connection conn = DriverManager.getConnection(getUrl(), props);) {
      conn.createStatement().execute("DROP TABLE " + tableName);
    }

    props.setProperty(QueryServices.IS_NAMESPACE_MAPPING_ENABLED, Boolean.TRUE.toString());
    try (Connection conn = DriverManager.getConnection(getUrl(), props);) {
      conn.createStatement().execute("CREATE SCHEMA " + schemaName);
    }
    try (Connection conn = DriverManager.getConnection(getUrl(), props);) {
      conn.createStatement().execute(ddl);
      assertNotEquals(null, admin.getDescriptor(
        TableName.valueOf(SchemaUtil.getPhysicalTableName(tableName.getBytes(), true).getName())));
    } finally {
      admin.close();
    }
    props.setProperty(QueryServices.DROP_METADATA_ATTRIB, Boolean.TRUE.toString());
    try (Connection conn = DriverManager.getConnection(getUrl(), props);) {
      conn.createStatement().execute("DROP TABLE " + tableName);
    }
  }

  @Test
  public void testCreateMultiTenantTable() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl =
      "CREATE TABLE  " + tableName + " (                TenantId UNSIGNED_INT NOT NULL ,\n"
        + "                Id UNSIGNED_INT NOT NULL ,\n" + "                val VARCHAR ,\n"
        + "                CONSTRAINT pk PRIMARY KEY(TenantId, Id) \n"
        + "                ) MULTI_TENANT=true";
    conn.createStatement().execute(ddl);
    conn = DriverManager.getConnection(getUrl(), props);
    try {
      conn.createStatement().execute(ddl);
      fail();
    } catch (TableAlreadyExistsException e) {
      // expected
    }
    conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute("DROP TABLE  " + tableName);
  }

  /**
   * Test that when the ddl only has PK cols, ttl is set.
   */
  @Test
  public void testCreateTableColumnFamilyHBaseAttribs1() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
      + " col1 integer NOT NULL," + " col2 bigint NOT NULL,"
      + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1, col2)" + " ) TTL=86400, SALT_BUCKETS = 4";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute(ddl);
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();
    ColumnFamilyDescriptor[] columnFamilies =
      admin.getDescriptor(TableName.valueOf(tableName)).getColumnFamilies();
    assertEquals(1, columnFamilies.length);
    assertEquals(86400, columnFamilies[0].getTimeToLive());
    // Check if TTL is stored in SYSCAT as well and we are getting ttl from get api in PTable
    TestUtil.assertTTLValue(conn, tableName, new LiteralTTLExpression(86400));
  }

  @Test
  public void testCreatingTooManyIndexesIsNotAllowed() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "CREATE TABLE " + tableName + " (\n" + "ID VARCHAR(15) PRIMARY KEY,\n"
      + "COL1 BIGINT," + "COL2 BIGINT," + "COL3 BIGINT," + "COL4 BIGINT) ";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute(ddl);

    int maxIndexes = conn.unwrap(PhoenixConnection.class).getQueryServices().getProps().getInt(
      QueryServices.MAX_INDEXES_PER_TABLE, QueryServicesOptions.DEFAULT_MAX_INDEXES_PER_TABLE);

    // Use local indexes since there's only one physical table for all of them.
    for (int i = 0; i < maxIndexes; i++) {
      conn.createStatement().execute("CREATE LOCAL INDEX I_" + i + tableName + " ON " + tableName
        + "(COL1) INCLUDE (COL2,COL3,COL4)");
    }

    // here we ensure we get a too many indexes error
    try {
      conn.createStatement().execute("CREATE LOCAL INDEX I_" + maxIndexes + tableName + " ON "
        + tableName + "(COL1) INCLUDE (COL2,COL3,COL4)");
      fail();
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.TOO_MANY_INDEXES.getErrorCode(), e.getErrorCode());
    }
  }

  /**
   * Tests that when: 1) DDL has both pk as well as key value columns 2) Key value columns have
   * different column family names 3) TTL specifier doesn't have column family name. Then: 1)TTL is
   * set. 2)All column families have the same TTL.
   */
  @Test
  public void testCreateTableColumnFamilyHBaseAttribs2() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
      + " col1 integer NOT NULL," + " b.col2 bigint," + " c.col3 bigint, "
      + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1)" + " ) TTL=86400, SALT_BUCKETS = 4";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute(ddl);
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();
    ColumnFamilyDescriptor[] columnFamilies =
      admin.getDescriptor(TableName.valueOf(tableName)).getColumnFamilies();
    assertEquals(2, columnFamilies.length);
    assertEquals(86400, columnFamilies[0].getTimeToLive());
    assertEquals("B", columnFamilies[0].getNameAsString());
    assertEquals(86400, columnFamilies[1].getTimeToLive());
    assertEquals("C", columnFamilies[1].getNameAsString());
    // Check if TTL is stored in SYSCAT as well and we are getting ttl from get api in PTable
    TestUtil.assertTTLValue(conn, tableName, new LiteralTTLExpression(86400));
  }

  /**
   * Tests that when: 1) DDL has both pk as well as key value columns 2) Key value columns have both
   * default and explicit column family names 3) TTL specifier doesn't have column family name.
   * Then: 1)TTL is set. 2)All column families have the same TTL.
   */
  @Test
  public void testCreateTableColumnFamilyHBaseAttribs3() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
      + " col1 integer NOT NULL," + " b.col2 bigint," + " col3 bigint, "
      + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1)" + " ) TTL=86400, SALT_BUCKETS = 4";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute(ddl);
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();
    ColumnFamilyDescriptor[] columnFamilies =
      admin.getDescriptor(TableName.valueOf(tableName)).getColumnFamilies();
    assertEquals(2, columnFamilies.length);
    assertEquals("0", columnFamilies[0].getNameAsString());
    assertEquals(86400, columnFamilies[0].getTimeToLive());
    assertEquals("B", columnFamilies[1].getNameAsString());
    assertEquals(86400, columnFamilies[1].getTimeToLive());
    // Check if TTL is stored in SYSCAT as well and we are getting ttl from get api in PTable
    TestUtil.assertTTLValue(conn, tableName, new LiteralTTLExpression(86400));
  }

  /**
   * Tests that when: 1) DDL has both pk as well as key value columns 2) Key value columns have both
   * default and explicit column family names 3) Block size specifier has the explicit column family
   * name. Then: 1)BLOCKSIZE is set. 2)The default column family has DEFAULT_BLOCKSIZE. 3)The
   * explicit column family has the BLOCK_SIZE specified in DDL.
   */
  @Test
  public void testCreateTableColumnFamilyHBaseAttribs4() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
      + " col1 integer NOT NULL," + " b.col2 bigint," + " col3 bigint, "
      + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1)" + " ) b.BLOCKSIZE=50000, SALT_BUCKETS = 4";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute(ddl);
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();
    ColumnFamilyDescriptor[] columnFamilies =
      admin.getDescriptor(TableName.valueOf(tableName)).getColumnFamilies();
    assertEquals(2, columnFamilies.length);
    assertEquals("0", columnFamilies[0].getNameAsString());
    assertEquals(ColumnFamilyDescriptorBuilder.DEFAULT_BLOCKSIZE, columnFamilies[0].getBlocksize());
    assertEquals("B", columnFamilies[1].getNameAsString());
    assertEquals(50000, columnFamilies[1].getBlocksize());
  }

  /**
   * Tests that when: 1) DDL has both pk as well as key value columns 2) Key value columns have
   * explicit column family names 3) Different BLOCKSIZE specifiers for different column family
   * names. Then: 1)BLOCKSIZE is set. 2)Each explicit column family has the BLOCKSIZE as specified
   * in DDL.
   */
  @Test
  public void testCreateTableColumnFamilyHBaseAttribs5() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
      + " col1 integer NOT NULL," + " b.col2 bigint," + " c.col3 bigint, "
      + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1)"
      + " ) b.BLOCKSIZE=50000, c.BLOCKSIZE=60000, SALT_BUCKETS = 4";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute(ddl);
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();
    ColumnFamilyDescriptor[] columnFamilies =
      admin.getDescriptor(TableName.valueOf(tableName)).getColumnFamilies();
    assertEquals(2, columnFamilies.length);
    assertEquals("B", columnFamilies[0].getNameAsString());
    assertEquals(50000, columnFamilies[0].getBlocksize());
    assertEquals("C", columnFamilies[1].getNameAsString());
    assertEquals(60000, columnFamilies[1].getBlocksize());
  }

  /**
   * Tests that when: 1) DDL has both pk as well as key value columns 2) There is a default column
   * family specified. Then: 1)TTL is set for the specified default column family.
   */
  @Test
  public void testCreateTableColumnFamilyHBaseAttribs6() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
      + " col1 integer NOT NULL," + " col2 bigint," + " col3 bigint, "
      + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1)"
      + " ) DEFAULT_COLUMN_FAMILY='a', TTL=10000, SALT_BUCKETS = 4";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute(ddl);
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();
    ColumnFamilyDescriptor[] columnFamilies =
      admin.getDescriptor(TableName.valueOf(tableName)).getColumnFamilies();
    assertEquals(1, columnFamilies.length);
    assertEquals("a", columnFamilies[0].getNameAsString());
    assertEquals(10000, columnFamilies[0].getTimeToLive());
    // Check if TTL is stored in SYSCAT as well and we are getting ttl from get api in PTable
    TestUtil.assertTTLValue(conn, tableName, new LiteralTTLExpression(10000));
  }

  /**
   * Tests that when: 1) DDL has only pk columns 2) There is a default column family specified.
   * Then: 1)TTL is set for the specified default column family.
   */
  @Test
  public void testCreateTableColumnFamilyHBaseAttribs7() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
      + " col1 integer NOT NULL," + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1)"
      + " ) DEFAULT_COLUMN_FAMILY='a', TTL=10000, SALT_BUCKETS = 4";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute(ddl);
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();
    ColumnFamilyDescriptor[] columnFamilies =
      admin.getDescriptor(TableName.valueOf(tableName)).getColumnFamilies();
    assertEquals(1, columnFamilies.length);
    assertEquals("a", columnFamilies[0].getNameAsString());
    assertEquals(10000, columnFamilies[0].getTimeToLive());
    // Check if TTL is stored in SYSCAT as well and we are getting ttl from get api in PTable
    TestUtil.assertTTLValue(conn, tableName, new LiteralTTLExpression(10000));
  }

  @Test
  public void testCreateTableColumnFamilyHBaseAttribs8() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
      + " col1 integer NOT NULL," + " col2 bigint NOT NULL,"
      + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1, col2)"
      + " ) BLOOMFILTER = 'NONE', SALT_BUCKETS = 4";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    conn.createStatement().execute(ddl);
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();
    ColumnFamilyDescriptor[] columnFamilies =
      admin.getDescriptor(TableName.valueOf(tableName)).getColumnFamilies();
    assertEquals(BloomType.NONE, columnFamilies[0].getBloomFilterType());
  }

  /**
   * Test to ensure that NOT NULL constraint isn't added to a non primary key column.
   */
  @Test
  public void testNotNullConstraintForNonPKColumn() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "CREATE TABLE IF NOT EXISTS " + tableName + " ( "
      + " ORGANIZATION_ID CHAR(15) NOT NULL, "
      + " EVENT_TIME DATE NOT NULL, USER_ID CHAR(15) NOT NULL, "
      + " ENTRY_POINT_ID CHAR(15) NOT NULL, ENTRY_POINT_TYPE CHAR(2) NOT NULL , "
      + " APEX_LIMIT_ID CHAR(15) NOT NULL,  USERNAME CHAR(80),  "
      + " NAMESPACE_PREFIX VARCHAR, ENTRY_POINT_NAME VARCHAR  NOT NULL , "
      + " EXECUTION_UNIT_NO VARCHAR, LIMIT_TYPE VARCHAR, " + " LIMIT_VALUE DOUBLE  "
      + " CONSTRAINT PK PRIMARY KEY ("
      + "     ORGANIZATION_ID, EVENT_TIME,USER_ID,ENTRY_POINT_ID, ENTRY_POINT_TYPE, APEX_LIMIT_ID "
      + " ) ) VERSIONS=1";

    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      conn.createStatement().execute(ddl);
      fail(" Non pk column ENTRY_POINT_NAME has a NOT NULL constraint");
    } catch (SQLException sqle) {
      assertEquals(SQLExceptionCode.KEY_VALUE_NOT_NULL.getErrorCode(), sqle.getErrorCode());
    }
  }

  @Test
  public void testNotNullConstraintForWithSinglePKCol() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table  " + tableName + " (k integer primary key, v bigint not null)";

    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      conn.createStatement().execute(ddl);
      fail(" Non pk column V has a NOT NULL constraint");
    } catch (SQLException sqle) {
      assertEquals(SQLExceptionCode.KEY_VALUE_NOT_NULL.getErrorCode(), sqle.getErrorCode());
    }
  }

  @Test
  public void testSpecifyingColumnFamilyForTTLFails() throws Exception {
    String tableName = generateUniqueName();
    String ddl = "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
      + " col1 integer NOT NULL," + " CF.col2 integer,"
      + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1)"
      + " ) DEFAULT_COLUMN_FAMILY='a', CF.TTL=10000, SALT_BUCKETS = 4";
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    try {
      conn.createStatement().execute(ddl);
    } catch (SQLException sqle) {
      assertEquals(SQLExceptionCode.COLUMN_FAMILY_NOT_ALLOWED_FOR_PROPERTY.getErrorCode(),
        sqle.getErrorCode());
    }
  }

  @Test
  public void testCreateTableWithoutSchema() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    props.setProperty(QueryServices.IS_NAMESPACE_MAPPING_ENABLED, Boolean.toString(true));
    String schemaName = generateUniqueName();
    String createSchemaDDL = "CREATE SCHEMA " + schemaName;
    ;
    String tableName = generateUniqueName();
    String createTableDDL =
      "CREATE TABLE " + schemaName + "." + tableName + " (pk INTEGER PRIMARY KEY)";
    String dropTableDDL = "DROP TABLE " + schemaName + "." + tableName;
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      try {
        conn.createStatement().execute(createTableDDL);
        fail();
      } catch (SchemaNotFoundException snfe) {
        // expected
      }
      conn.createStatement().execute(createSchemaDDL);
    }
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.createStatement().execute(createTableDDL);
    }
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.createStatement().execute(dropTableDDL);
    }
    props.setProperty(QueryServices.IS_NAMESPACE_MAPPING_ENABLED, Boolean.toString(false));
    try (Connection conn = DriverManager.getConnection(getUrl(), props);) {
      conn.createStatement().execute(createTableDDL);
    } catch (SchemaNotFoundException e) {
      fail();
    }
  }

  @Test
  public void testCreateTableIfNotExistsForEncodedColumnNames() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    String tableName = generateUniqueName();
    String createTableDDL = "CREATE TABLE IF NOT EXISTS " + tableName + " (pk INTEGER PRIMARY KEY)";
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.createStatement().execute(createTableDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.TWO_BYTE_QUALIFIERS,
        ImmutableStorageScheme.ONE_CELL_PER_COLUMN, tableName, conn);
    }
    // Execute the ddl again
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.createStatement().execute(createTableDDL);
      ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + tableName);
      assertFalse(rs.next());
      assertColumnEncodingMetadata(QualifierEncodingScheme.TWO_BYTE_QUALIFIERS,
        ImmutableStorageScheme.ONE_CELL_PER_COLUMN, tableName, conn);
    }
    // Now execute the ddl with a different COLUMN_ENCODED_BYTES. This shouldn't change the
    // original encoded bytes setting.
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.createStatement().execute(createTableDDL + " COLUMN_ENCODED_BYTES = 1");
      ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + tableName);
      assertFalse(rs.next());
      assertColumnEncodingMetadata(QualifierEncodingScheme.TWO_BYTE_QUALIFIERS,
        ImmutableStorageScheme.ONE_CELL_PER_COLUMN, tableName, conn);
    }
    // Now execute the ddl where COLUMN_ENCODED_BYTES=0. This shouldn't change the original
    // encoded bytes setting.
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.createStatement().execute(createTableDDL + " COLUMN_ENCODED_BYTES = 0");
      ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + tableName);
      assertFalse(rs.next());
      assertColumnEncodingMetadata(QualifierEncodingScheme.TWO_BYTE_QUALIFIERS,
        ImmutableStorageScheme.ONE_CELL_PER_COLUMN, tableName, conn);
    }

  }

  private void assertColumnEncodingMetadata(QualifierEncodingScheme expectedEncodingScheme,
    ImmutableStorageScheme expectedStorageScheme, String tableName, Connection conn)
    throws Exception {
    PhoenixConnection phxConn = conn.unwrap(PhoenixConnection.class);
    PTable table = phxConn.getTable(new PTableKey(null, tableName));
    assertEquals(expectedEncodingScheme, table.getEncodingScheme());
    assertEquals(expectedStorageScheme, table.getImmutableStorageScheme());
  }

  @Test
  public void testMultiTenantImmutableTableMetadata() throws Exception {
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    String nonEncodedOneCellPerColumnMultiTenantTable = generateUniqueName();
    String twoByteQualifierEncodedOneCellPerColumnMultiTenantTable = generateUniqueName();
    String oneByteQualifierEncodedOneCellPerColumnMultiTenantTable = generateUniqueName();
    String twoByteQualifierSingleCellArrayWithOffsetsMultitenantTable = generateUniqueName();
    String oneByteQualifierSingleCellArrayWithOffsetsMultitenantTable = generateUniqueName();
    String createTableDDL;
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      createTableDDL = "create IMMUTABLE TABLE " + nonEncodedOneCellPerColumnMultiTenantTable + " ("
        + " id char(1) NOT NULL," + " col1 integer NOT NULL," + " col2 bigint NOT NULL,"
        + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1, col2)) MULTI_TENANT=true, COLUMN_ENCODED_BYTES=0";
      conn.createStatement().execute(createTableDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.NON_ENCODED_QUALIFIERS,
        ImmutableStorageScheme.ONE_CELL_PER_COLUMN, nonEncodedOneCellPerColumnMultiTenantTable,
        conn);
    }
    props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      createTableDDL =
        "create IMMUTABLE table " + twoByteQualifierEncodedOneCellPerColumnMultiTenantTable + " ("
          + " id char(1) NOT NULL," + " col1 integer NOT NULL," + " col2 bigint NOT NULL,"
          + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1, col2)) MULTI_TENANT=true";
      conn.createStatement().execute(createTableDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.TWO_BYTE_QUALIFIERS,
        ImmutableStorageScheme.ONE_CELL_PER_COLUMN,
        twoByteQualifierEncodedOneCellPerColumnMultiTenantTable, conn);
    }
    props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      createTableDDL = "create IMMUTABLE table "
        + oneByteQualifierEncodedOneCellPerColumnMultiTenantTable + " (" + " id char(1) NOT NULL,"
        + " col1 integer NOT NULL," + " col2 bigint NOT NULL,"
        + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1, col2)) MULTI_TENANT=true, COLUMN_ENCODED_BYTES = 1";
      conn.createStatement().execute(createTableDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.ONE_BYTE_QUALIFIERS,
        ImmutableStorageScheme.ONE_CELL_PER_COLUMN,
        oneByteQualifierEncodedOneCellPerColumnMultiTenantTable, conn);
    }
    props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      createTableDDL = "create IMMUTABLE table "
        + twoByteQualifierSingleCellArrayWithOffsetsMultitenantTable + " ("
        + " id char(1) NOT NULL," + " col1 integer NOT NULL," + " col2 bigint NOT NULL,"
        + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1, col2)) MULTI_TENANT=true, IMMUTABLE_STORAGE_SCHEME=SINGLE_CELL_ARRAY_WITH_OFFSETS";
      conn.createStatement().execute(createTableDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.TWO_BYTE_QUALIFIERS,
        ImmutableStorageScheme.SINGLE_CELL_ARRAY_WITH_OFFSETS,
        twoByteQualifierSingleCellArrayWithOffsetsMultitenantTable, conn);
    }
    props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      createTableDDL = "create IMMUTABLE table "
        + oneByteQualifierSingleCellArrayWithOffsetsMultitenantTable + " ("
        + " id char(1) NOT NULL," + " col1 integer NOT NULL," + " col2 bigint NOT NULL,"
        + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1, col2)) MULTI_TENANT=true, IMMUTABLE_STORAGE_SCHEME=SINGLE_CELL_ARRAY_WITH_OFFSETS, COLUMN_ENCODED_BYTES=1";
      conn.createStatement().execute(createTableDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.ONE_BYTE_QUALIFIERS,
        ImmutableStorageScheme.SINGLE_CELL_ARRAY_WITH_OFFSETS,
        oneByteQualifierSingleCellArrayWithOffsetsMultitenantTable, conn);

    }
  }

  @Test
  public void testCreateChangeDetectionEnabledTable() throws Exception {
    // create a table with CHANGE_DETECTION_ENABLED and verify both that it's set properly
    // on the PTable, and that it gets persisted to the external schema registry

    String schemaName = generateUniqueName();
    String tableName = generateUniqueName();
    String fullTableName = SchemaUtil.getTableName(schemaName, tableName);
    try (PhoenixConnection conn = (PhoenixConnection) DriverManager.getConnection(getUrl())) {
      String ddl = "CREATE TABLE " + fullTableName + " (id char(1) NOT NULL,"
        + " col1 integer NOT NULL," + " col2 bigint NOT NULL,"
        + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1, col2)) " + "CHANGE_DETECTION_ENABLED=true";
      conn.createStatement().execute(ddl);
      PTable table = conn.getTableNoCache(fullTableName);
      assertTrue(table.isChangeDetectionEnabled());
      AlterTableIT.verifySchemaExport(table, getUtility().getConfiguration());
    }
  }

  @Test
  public void testCreateIndexWithDifferentStorageAndEncoding() throws Exception {
    verifyIndexSchemeChange(false, false);
    verifyIndexSchemeChange(false, true);
    verifyIndexSchemeChange(true, false);
    verifyIndexSchemeChange(true, true);

    String tableName = generateUniqueName();
    String indexName = generateUniqueName();
    String createTableDDL = "create IMMUTABLE TABLE " + tableName
      + "(id char(1) NOT NULL, col1 char(1), col2 char(1) "
      + "CONSTRAINT NAME_PK PRIMARY KEY (id)) IMMUTABLE_STORAGE_SCHEME=SINGLE_CELL_ARRAY_WITH_OFFSETS";
    String createIndexDDL = "create INDEX " + indexName + " ON " + tableName
      + " (col1) INCLUDE (col2) IMMUTABLE_STORAGE_SCHEME=ONE_CELL_PER_COLUMN";

    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.createStatement().execute(createTableDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.TWO_BYTE_QUALIFIERS,
        ImmutableStorageScheme.SINGLE_CELL_ARRAY_WITH_OFFSETS, tableName, conn);

      boolean failed = false;
      try {
        conn.createStatement().execute(createIndexDDL);
      } catch (SQLException e) {
        assertEquals(e.getErrorCode(),
          SQLExceptionCode.INVALID_IMMUTABLE_STORAGE_SCHEME_CHANGE.getErrorCode());
        failed = true;
      }
      assertEquals(true, failed);
    }
  }

  private void verifyIndexSchemeChange(boolean immutable, boolean multiTenant) throws Exception {
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    String nonEncodedOneCellPerColumnTable = generateUniqueName();
    String createTableDDL;
    String createIndexDDL;

    String createTableBaseDDL = "create " + (immutable ? " IMMUTABLE " : "") + " TABLE %s ("
      + " id char(1) NOT NULL," + " col1 integer NOT NULL," + " col2 bigint NOT NULL,"
      + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1, col2)) MULTI_TENANT="
      + (multiTenant ? "true," : "false,");

    String createIndexBaseDDL = "create index %s ON %s (col1) INCLUDE (col2) ";

    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      createTableDDL = String.format(createTableBaseDDL, nonEncodedOneCellPerColumnTable);
      createTableDDL += "IMMUTABLE_STORAGE_SCHEME=ONE_CELL_PER_COLUMN, COLUMN_ENCODED_BYTES=0";
      conn.createStatement().execute(createTableDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.NON_ENCODED_QUALIFIERS,
        ImmutableStorageScheme.ONE_CELL_PER_COLUMN, nonEncodedOneCellPerColumnTable, conn);

      String idxName = "IDX_" + generateUniqueName();
      // Don't specify anything to see if it inherits from parent
      createIndexDDL = String.format(createIndexBaseDDL, idxName, nonEncodedOneCellPerColumnTable);
      conn.createStatement().execute(createIndexDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.NON_ENCODED_QUALIFIERS,
        ImmutableStorageScheme.ONE_CELL_PER_COLUMN, idxName, conn);

      idxName = "IDX_" + generateUniqueName();
      createIndexDDL = String.format(createIndexBaseDDL, idxName, nonEncodedOneCellPerColumnTable);
      createIndexDDL += "IMMUTABLE_STORAGE_SCHEME=SINGLE_CELL_ARRAY_WITH_OFFSETS";
      conn.createStatement().execute(createIndexDDL);
      // Check if it sets the encoding to 2
      assertColumnEncodingMetadata(QualifierEncodingScheme.TWO_BYTE_QUALIFIERS,
        ImmutableStorageScheme.SINGLE_CELL_ARRAY_WITH_OFFSETS, idxName, conn);

      idxName = "IDX_" + generateUniqueName();
      createIndexDDL = String.format(createIndexBaseDDL, idxName, nonEncodedOneCellPerColumnTable);
      createIndexDDL +=
        "IMMUTABLE_STORAGE_SCHEME=SINGLE_CELL_ARRAY_WITH_OFFSETS, COLUMN_ENCODED_BYTES=3";
      conn.createStatement().execute(createIndexDDL);
      assertColumnEncodingMetadata(QualifierEncodingScheme.THREE_BYTE_QUALIFIERS,
        ImmutableStorageScheme.SINGLE_CELL_ARRAY_WITH_OFFSETS, idxName, conn);

      createIndexDDL = String.format(createIndexBaseDDL, idxName, nonEncodedOneCellPerColumnTable);
      createIndexDDL +=
        "IMMUTABLE_STORAGE_SCHEME=SINGLE_CELL_ARRAY_WITH_OFFSETS, COLUMN_ENCODED_BYTES=0";
      // should fail
      boolean failed = false;
      try {
        conn.createStatement().execute(createIndexDDL);
      } catch (SQLException e) {
        failed = true;
        assertEquals(SQLExceptionCode.INVALID_IMMUTABLE_STORAGE_SCHEME_AND_COLUMN_QUALIFIER_BYTES
          .getErrorCode(), e.getErrorCode());
      }
      assertEquals(true, failed);
    }
  }

  private void verifyUCFValueInSysCat(String tableName, String createTableString, Properties props,
    long expectedUCFInSysCat) throws SQLException {
    String readSysCatQuery = "SELECT TABLE_NAME, UPDATE_CACHE_FREQUENCY FROM SYSTEM.CATALOG "
      + "WHERE TABLE_NAME = '" + tableName + "'  AND TABLE_TYPE='u'";

    try (Connection connection = DriverManager.getConnection(getUrl(), props);
      Statement stmt = connection.createStatement()) {
      stmt.execute(createTableString);
      try (ResultSet rs = stmt.executeQuery(readSysCatQuery)) {
        assertTrue(rs.next());
        assertEquals(expectedUCFInSysCat, rs.getLong(2));
      }
      stmt.execute("drop table " + tableName);
    }
  }

  @Test
  public void testCreateTableNoUpdateCacheFreq() throws Exception {
    String tableName = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
    String createTableString =
      "CREATE TABLE " + tableName + " (k VARCHAR PRIMARY KEY, " + "v1 VARCHAR, v2 VARCHAR)";
    verifyUCFValueInSysCat(tableName, createTableString, props,
      QueryServicesOptions.DEFAULT_UPDATE_CACHE_FREQUENCY);
  }

  @Test
  public void testCreateTableWithTableLevelUpdateCacheFreq() throws Exception {
    String tableName = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);

    HashMap<String, Long> expectedUCF = new HashMap<>();
    expectedUCF.put("10", new Long(10L));
    expectedUCF.put("0", new Long(0L));
    expectedUCF.put("10000", new Long(10000L));
    expectedUCF.put("ALWAYS", new Long(0L));
    expectedUCF.put("NEVER", new Long(Long.MAX_VALUE));

    for (HashMap.Entry<String, Long> entry : expectedUCF.entrySet()) {
      String tableLevelUCF = entry.getKey();
      long expectedUCFInSysCat = entry.getValue();

      String createTableString = "CREATE TABLE " + tableName + " (k VARCHAR PRIMARY KEY,"
        + "v1 VARCHAR, v2 VARCHAR) UPDATE_CACHE_FREQUENCY = " + tableLevelUCF;
      verifyUCFValueInSysCat(tableName, createTableString, props, expectedUCFInSysCat);
    }
  }

  @Test
  public void testCreateTableWithInvalidTableUpdateCacheFreqShouldThrow() throws Exception {
    String tableName = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);

    ArrayList<String> invalidUCF = new ArrayList<>();
    invalidUCF.add("GIBBERISH");
    invalidUCF.add("10000.6");

    for (String tableLevelUCF : invalidUCF) {
      String createTableString = "CREATE TABLE " + tableName + " (k VARCHAR PRIMARY KEY,"
        + "v1 VARCHAR, v2 VARCHAR) UPDATE_CACHE_FREQUENCY = " + tableLevelUCF;
      try {
        verifyUCFValueInSysCat(tableName, createTableString, props, -1L);
        fail();
      } catch (IllegalArgumentException e) {
        // expected
        assertTrue(
          e.getMessage().contains("Table's " + PhoenixDatabaseMetaData.UPDATE_CACHE_FREQUENCY));
      }
    }
  }

  @Test
  public void testCreateTableWithConnLevelUpdateCacheFreq() throws Exception {
    String tableName = generateUniqueName();
    Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);

    HashMap<String, Long> expectedUCF = new HashMap<>();
    expectedUCF.put("10", new Long(10L));
    expectedUCF.put("0", new Long(0L));
    expectedUCF.put("10000", new Long(10000L));
    expectedUCF.put("ALWAYS", new Long(0L));
    expectedUCF.put("NEVER", new Long(Long.MAX_VALUE));

    for (HashMap.Entry<String, Long> entry : expectedUCF.entrySet()) {
      String connLevelUCF = entry.getKey();
      long expectedUCFInSysCat = entry.getValue();

      String createTableString =
        "CREATE TABLE " + tableName + " (k VARCHAR PRIMARY KEY," + "v1 VARCHAR, v2 VARCHAR)";
      props.put(QueryServices.DEFAULT_UPDATE_CACHE_FREQUENCY_ATRRIB, connLevelUCF);
      verifyUCFValueInSysCat(tableName, createTableString, props, expectedUCFInSysCat);
    }
  }

  @Test
  public void testCreateTableWithNamespaceMappingEnabled() throws Exception {
    final String NS = "NS_" + generateUniqueName();
    final String TBL = "TBL_" + generateUniqueName();
    final String CF = "CF";

    Properties props = new Properties();
    props.setProperty(QueryServices.IS_NAMESPACE_MAPPING_ENABLED, Boolean.TRUE.toString());

    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.createStatement().execute("CREATE SCHEMA " + NS);

      // test for a table that is in non-default schema
      {
        String table = NS + "." + TBL;
        conn.createStatement()
          .execute("CREATE TABLE " + table + " (PK VARCHAR PRIMARY KEY, " + CF + ".COL VARCHAR)");

        assertTrue(QueryUtil
          .getExplainPlan(conn.createStatement().executeQuery("explain select * from " + table))
          .contains(NS + ":" + TBL));

        conn.createStatement().execute("DROP TABLE " + table);
      }

      // test for a table whose name contains a dot (e.g. "AAA.BBB") in default schema
      {
        String table = "\"" + NS + "." + TBL + "\"";
        conn.createStatement()
          .execute("CREATE TABLE " + table + " (PK VARCHAR PRIMARY KEY, " + CF + ".COL VARCHAR)");

        assertTrue(QueryUtil
          .getExplainPlan(conn.createStatement().executeQuery("explain select * from " + table))
          .contains(NS + "." + TBL));

        conn.createStatement().execute("DROP TABLE " + table);
      }

      // test for a view whose name contains a dot (e.g. "AAA.BBB") in non-default schema
      {
        String table = NS + ".\"" + NS + "." + TBL + "\"";
        conn.createStatement()
          .execute("CREATE TABLE " + table + " (PK VARCHAR PRIMARY KEY, " + CF + ".COL VARCHAR)");

        assertTrue(QueryUtil
          .getExplainPlan(conn.createStatement().executeQuery("explain select * from " + table))
          .contains(NS + ":" + NS + "." + TBL));

        conn.createStatement().execute("DROP TABLE " + table);
      }

      conn.createStatement().execute("DROP SCHEMA " + NS);
    }
  }

  @Test
  public void testSetTableDescriptorPropertyOnView() throws Exception {
    Properties props = new Properties();
    final String dataTableFullName = generateUniqueName();
    String ddl = "CREATE TABLE " + dataTableFullName + " (\n" + "ID1 VARCHAR(15) NOT NULL,\n"
      + "ID2 VARCHAR(15) NOT NULL,\n" + "CREATED_DATE DATE,\n" + "CREATION_TIME BIGINT,\n"
      + "LAST_USED DATE,\n" + "CONSTRAINT PK PRIMARY KEY (ID1, ID2)) ";
    Connection conn1 = DriverManager.getConnection(getUrl(), props);
    conn1.createStatement().execute(ddl);
    conn1.close();
    final String viewFullName = generateUniqueName();
    Connection conn2 = DriverManager.getConnection(getUrl(), props);
    ddl = "CREATE VIEW " + viewFullName + " AS SELECT * FROM " + dataTableFullName
      + " WHERE CREATION_TIME = 1 THROW_INDEX_WRITE_FAILURE = FALSE";
    try {
      conn2.createStatement().execute(ddl);
      fail();
    } catch (SQLException e) {
      assertEquals(SQLExceptionCode.VIEW_WITH_PROPERTIES.getErrorCode(), e.getErrorCode());
    }
    conn2.close();
  }

  @Test
  public void testCreateViewFromNonExistentTable() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      conn.createStatement()
        .execute("CREATE TABLE IF NOT EXISTS S.T1 (A INTEGER PRIMARY KEY, B INTEGER)");
      // 1. create view from non-existent table (without schema)
      try {
        conn.createStatement()
          .execute("CREATE VIEW IF NOT EXISTS V1(C INTEGER) AS SELECT * FROM T2");
        fail("Creating view on non-existent table should have failed");
      } catch (TableNotFoundException e) {
        assertEquals("T2", e.getTableName());
        assertEquals("", e.getSchemaName());
        assertEquals("ERROR 1012 (42M03): Table undefined. tableName=T2", e.getMessage());
      }
      // 2. create view from existing table - successful
      conn.createStatement()
        .execute("CREATE VIEW IF NOT EXISTS V1(C INTEGER) AS SELECT * FROM S.T1");
      // 3. create view from non-existent table (with schema)
      try {
        conn.createStatement()
          .execute("CREATE VIEW IF NOT EXISTS V2(C INTEGER) AS SELECT * FROM S.T2");
        fail("Creating view on non-existent table should have failed");
      } catch (TableNotFoundException e) {
        assertEquals("T2", e.getTableName());
        assertEquals("S", e.getSchemaName());
        assertEquals("ERROR 1012 (42M03): Table undefined. tableName=S.T2", e.getMessage());
      }
    }
  }

  @Test
  public void testSettingGuidePostWidth() throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      String dataTable = generateUniqueName();
      int guidePostWidth = 20;
      String ddl = "CREATE TABLE " + dataTable + " (k INTEGER PRIMARY KEY, a bigint, b bigint)"
        + " GUIDE_POSTS_WIDTH=" + guidePostWidth;
      conn.createStatement().execute(ddl);
      assertEquals(20, checkGuidePostWidth(dataTable));
      String viewName = "V_" + generateUniqueName();
      ddl = "CREATE VIEW " + viewName + " AS SELECT * FROM " + dataTable + " GUIDE_POSTS_WIDTH="
        + guidePostWidth;
      try {
        conn.createStatement().execute(ddl);
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.CANNOT_SET_GUIDE_POST_WIDTH.getErrorCode(), e.getErrorCode());
      }

      // let the view creation go through
      ddl = "CREATE VIEW " + viewName + " AS SELECT * FROM " + dataTable;
      conn.createStatement().execute(ddl);

      String globalIndex = "GI_" + generateUniqueName();
      ddl = "CREATE INDEX " + globalIndex + " ON " + dataTable
        + "(a) INCLUDE (b) GUIDE_POSTS_WIDTH = " + guidePostWidth;
      try {
        conn.createStatement().execute(ddl);
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.CANNOT_SET_GUIDE_POST_WIDTH.getErrorCode(), e.getErrorCode());
      }
      String localIndex = "LI_" + generateUniqueName();
      ddl = "CREATE LOCAL INDEX " + localIndex + " ON " + dataTable
        + "(b) INCLUDE (a) GUIDE_POSTS_WIDTH = " + guidePostWidth;
      try {
        conn.createStatement().execute(ddl);
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.CANNOT_SET_GUIDE_POST_WIDTH.getErrorCode(), e.getErrorCode());
      }
      String viewIndex = "VI_" + generateUniqueName();
      ddl = "CREATE LOCAL INDEX " + viewIndex + " ON " + dataTable
        + "(b) INCLUDE (a) GUIDE_POSTS_WIDTH = " + guidePostWidth;
      try {
        conn.createStatement().execute(ddl);
      } catch (SQLException e) {
        assertEquals(SQLExceptionCode.CANNOT_SET_GUIDE_POST_WIDTH.getErrorCode(), e.getErrorCode());
      }
    }
  }

  /**
   * Ensure that HTD contains table priorities correctly.
   */
  @Test
  public void testTableDescriptorPriority() throws SQLException, IOException {
    String tableName = "TBL_" + generateUniqueName();
    String indexName = "IND_" + generateUniqueName();
    String fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);
    String fullIndexeName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, indexName);
    // Check system tables priorities.
    try (Admin admin = driver.getConnectionQueryServices(getUrl(), new Properties()).getAdmin();
      Connection c = DriverManager.getConnection(getUrl())) {
      ResultSet rs =
        c.getMetaData().getTables("", "\"" + PhoenixDatabaseMetaData.SYSTEM_CATALOG_SCHEMA + "\"",
          null, new String[] { PTableType.SYSTEM.toString() });
      ReadOnlyProps p = c.unwrap(PhoenixConnection.class).getQueryServices().getProps();
      while (rs.next()) {
        String schemaName = rs.getString(PhoenixDatabaseMetaData.TABLE_SCHEM);
        String tName = rs.getString(PhoenixDatabaseMetaData.TABLE_NAME);
        org.apache.hadoop.hbase.TableName hbaseTableName =
          SchemaUtil.getPhysicalTableName(SchemaUtil.getTableName(schemaName, tName), p);
        TableDescriptor htd = admin.getDescriptor(hbaseTableName);
        String val = htd.getValue("PRIORITY");
        assertNotNull("PRIORITY is not set for table:" + htd, val);
        assertTrue(Integer.parseInt(val) >= IndexUtil.getMetadataPriority(config));
      }
      Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
      String ddl = "CREATE TABLE " + fullTableName + TestUtil.TEST_TABLE_SCHEMA;
      try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
        conn.setAutoCommit(false);
        Statement stmt = conn.createStatement();
        stmt.execute(ddl);
        BaseTest.populateTestTable(fullTableName);
        ddl = "CREATE INDEX " + indexName + " ON " + fullTableName + " (long_col1, long_col2)"
          + " INCLUDE (decimal_col1, decimal_col2)";
        stmt.execute(ddl);
      }

      TableDescriptor dataTable =
        admin.getDescriptor(org.apache.hadoop.hbase.TableName.valueOf(fullTableName));
      String val = dataTable.getValue("PRIORITY");
      assertTrue(val == null || Integer.parseInt(val) < HConstants.HIGH_QOS);

      TableDescriptor indexTable =
        admin.getDescriptor(org.apache.hadoop.hbase.TableName.valueOf(fullIndexeName));
      val = indexTable.getValue("PRIORITY");
      assertNotNull("PRIORITY is not set for table:" + indexTable, val);
      assertTrue(Integer.parseInt(val) >= IndexUtil.getIndexPriority(config));
    }
  }

  @Test
  public void testCreateTableSchemaVersionAndTopicName() throws Exception {
    Properties props = new Properties();
    final String schemaName = generateUniqueName();
    final String tableName = generateUniqueName();
    final String version = "V1.0";
    final String topicName = "MyTopicName";
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      testCreateTableSchemaVersionAndTopicNameHelper(conn, schemaName, tableName, version,
        topicName);
    }
  }

  public static void testCreateTableSchemaVersionAndTopicNameHelper(Connection conn,
    String schemaName, String tableName, String dataTableVersion, String topicName)
    throws Exception {
    final String dataTableFullName = SchemaUtil.getTableName(schemaName, tableName);
    String ddl = "CREATE TABLE " + dataTableFullName + " (\n" + "ID1 VARCHAR(15) NOT NULL,\n"
      + "ID2 VARCHAR(15) NOT NULL,\n" + "CREATED_DATE DATE,\n" + "CREATION_TIME BIGINT,\n"
      + "LAST_USED DATE,\n" + "CONSTRAINT PK PRIMARY KEY (ID1, ID2)) SCHEMA_VERSION='"
      + dataTableVersion + "'";
    if (topicName != null) {
      ddl += ", STREAMING_TOPIC_NAME='" + topicName + "'";
    }
    conn.createStatement().execute(ddl);
    PTable table = conn.unwrap(PhoenixConnection.class).getTableNoCache(dataTableFullName);
    assertEquals(dataTableVersion, table.getSchemaVersion());
    if (topicName != null) {
      assertEquals(topicName, table.getStreamingTopicName());
    } else {
      assertNull(table.getStreamingTopicName());
    }
  }

  @Test
  public void testCreateTableDDLTimestamp() throws Exception {
    Properties props = new Properties();
    final String schemaName = generateUniqueName();
    final String tableName = generateUniqueName();
    final String dataTableFullName = SchemaUtil.getTableName(schemaName, tableName);
    String ddl = "CREATE TABLE " + dataTableFullName + " (\n" + "ID1 VARCHAR(15) NOT NULL,\n"
      + "ID2 VARCHAR(15) NOT NULL,\n" + "CREATED_DATE DATE,\n" + "CREATION_TIME BIGINT,\n"
      + "LAST_USED DATE,\n" + "CONSTRAINT PK PRIMARY KEY (ID1, ID2)) ";
    long startTS = EnvironmentEdgeManager.currentTimeMillis();
    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
      conn.createStatement().execute(ddl);
      verifyLastDDLTimestamp(dataTableFullName, startTS, conn);
    }
  }

  @Test
  public void testCreateTableWithColumnQualifiers() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl = "CREATE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT INTEGER ENCODED_QUALIFIER 11, INT2 INTEGER ENCODED_QUALIFIER 12, "
      + "INT3 INTEGER ENCODED_QUALIFIER 14) COLUMN_QUALIFIER_COUNTER ('"
      + QueryConstants.DEFAULT_COLUMN_FAMILY + "'=15)";
    conn.createStatement().execute(ddl);
    PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
    PTable table = pconn.getTable(new PTableKey(null, tableName));

    QualifierEncodingScheme encodingScheme = table.getEncodingScheme();
    assertNotEquals(PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS, encodingScheme);

    PTable.EncodedCQCounter cqCounter = table.getEncodedCQCounter();
    assertEquals(15, cqCounter.values().get(QueryConstants.DEFAULT_COLUMN_FAMILY).intValue());
    assertEquals(11,
      encodingScheme.decode(table.getColumnForColumnName("INT").getColumnQualifierBytes()));
    assertEquals(12,
      encodingScheme.decode(table.getColumnForColumnName("INT2").getColumnQualifierBytes()));
    assertEquals(14,
      encodingScheme.decode(table.getColumnForColumnName("INT3").getColumnQualifierBytes()));
  }

  @Test
  public void testCreateTableWithNotOrderedColumnQualifiers() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl = "CREATE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT3 INTEGER ENCODED_QUALIFIER 14, INT INTEGER ENCODED_QUALIFIER 11, "
      + "INT2 INTEGER ENCODED_QUALIFIER 12) COLUMN_QUALIFIER_COUNTER ('"
      + QueryConstants.DEFAULT_COLUMN_FAMILY + "'=15)";
    conn.createStatement().execute(ddl);
    PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
    PTable table = pconn.getTable(new PTableKey(null, tableName));

    QualifierEncodingScheme encodingScheme = table.getEncodingScheme();
    assertNotEquals(PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS, encodingScheme);

    PTable.EncodedCQCounter cqCounter = table.getEncodedCQCounter();
    assertEquals(15, cqCounter.values().get(QueryConstants.DEFAULT_COLUMN_FAMILY).intValue());
    assertEquals(11,
      encodingScheme.decode(table.getColumnForColumnName("INT").getColumnQualifierBytes()));
    assertEquals(12,
      encodingScheme.decode(table.getColumnForColumnName("INT2").getColumnQualifierBytes()));
    assertEquals(14,
      encodingScheme.decode(table.getColumnForColumnName("INT3").getColumnQualifierBytes()));
  }

  @Test
  public void testCreateTableWithColumnQualifiersWithoutCounter() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl = "CREATE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT INTEGER ENCODED_QUALIFIER 11, INT2 INTEGER ENCODED_QUALIFIER 12, "
      + "INT3 INTEGER ENCODED_QUALIFIER 14)";
    conn.createStatement().execute(ddl);
    PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
    PTable table = pconn.getTable(new PTableKey(null, tableName));

    QualifierEncodingScheme encodingScheme = table.getEncodingScheme();
    assertNotEquals(PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS, encodingScheme);

    PTable.EncodedCQCounter cqCounter = table.getEncodedCQCounter();
    assertEquals(15, cqCounter.values().get(QueryConstants.DEFAULT_COLUMN_FAMILY).intValue());
    assertEquals(11,
      encodingScheme.decode(table.getColumnForColumnName("INT").getColumnQualifierBytes()));
    assertEquals(12,
      encodingScheme.decode(table.getColumnForColumnName("INT2").getColumnQualifierBytes()));
    assertEquals(14,
      encodingScheme.decode(table.getColumnForColumnName("INT3").getColumnQualifierBytes()));
  }

  @Test
  public void testCreateTableWithColumnQualifiersMultipleFamilies() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl = "CREATE IMMUTABLE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "A.INT INTEGER ENCODED_QUALIFIER 11, A.INT2 INTEGER ENCODED_QUALIFIER 13, "
      + "B.INT3 INTEGER ENCODED_QUALIFIER 12) " + "COLUMN_QUALIFIER_COUNTER ('A'=14, 'B'=13)";
    conn.createStatement().execute(ddl);
    PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
    PTable table = pconn.getTable(new PTableKey(null, tableName));

    QualifierEncodingScheme encodingScheme = table.getEncodingScheme();
    assertNotEquals(PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS, encodingScheme);

    PTable.EncodedCQCounter cqCounter = table.getEncodedCQCounter();
    assertEquals(14, cqCounter.values().get("A").intValue());
    assertEquals(13, cqCounter.values().get("B").intValue());
    assertEquals(11,
      encodingScheme.decode(table.getColumnForColumnName("INT").getColumnQualifierBytes()));
    assertEquals(13,
      encodingScheme.decode(table.getColumnForColumnName("INT2").getColumnQualifierBytes()));
    assertEquals(12,
      encodingScheme.decode(table.getColumnForColumnName("INT3").getColumnQualifierBytes()));
  }

  @Test
  public void testCreateTableWithColumnQualifiersMultipleFamiliesWithoutCounter() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl = "CREATE IMMUTABLE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "A.INT INTEGER ENCODED_QUALIFIER 11, A.INT2 INTEGER ENCODED_QUALIFIER 13, "
      + "B.INT3 INTEGER ENCODED_QUALIFIER 12)";
    conn.createStatement().execute(ddl);
    PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
    PTable table = pconn.getTable(new PTableKey(null, tableName));

    QualifierEncodingScheme encodingScheme = table.getEncodingScheme();
    assertNotEquals(PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS, encodingScheme);

    PTable.EncodedCQCounter cqCounter = table.getEncodedCQCounter();
    assertEquals(14, cqCounter.values().get("A").intValue());
    assertEquals(13, cqCounter.values().get("B").intValue());
    assertEquals(11,
      encodingScheme.decode(table.getColumnForColumnName("INT").getColumnQualifierBytes()));
    assertEquals(13,
      encodingScheme.decode(table.getColumnForColumnName("INT2").getColumnQualifierBytes()));
    assertEquals(12,
      encodingScheme.decode(table.getColumnForColumnName("INT3").getColumnQualifierBytes()));
  }

  @Test
  public void testCreateTableWithColumnQualifiersDuplicateCQ() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl = "CREATE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT INTEGER ENCODED_QUALIFIER 11, INT2 INTEGER ENCODED_QUALIFIER 11, "
      + "INT3 INTEGER ENCODED_QUALIFIER 14) COLUMN_QUALIFIER_COUNTER ('0'=15)";
    try {
      conn.createStatement().execute(ddl);
      fail("Duplicate Column Qualifiers");
    } catch (SQLException e) {
      // expected DUPLICATE_CQ
      if (e.getErrorCode() != SQLExceptionCode.DUPLICATE_CQ.getErrorCode()) {
        fail("Duplicate Column Qualifiers");
      }
    }
  }

  @Test
  public void testCreateTableInvalidColumnQualifier() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl = "CREATE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT INTEGER ENCODED_QUALIFIER 11, INT2 INTEGER ENCODED_QUALIFIER 9, "
      + "INT3 INTEGER ENCODED_QUALIFIER 14) COLUMN_QUALIFIER_COUNTER ('0'=15)";
    try {
      conn.createStatement().execute(ddl);
      fail("Invalid Column Qualifier");
    } catch (SQLException e) {
      // expected INVALID_CQ
      if (e.getErrorCode() != SQLExceptionCode.INVALID_CQ.getErrorCode()) {
        fail("Invalid Column Qualifier");
      }
    }

    ddl = "CREATE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT INTEGER ENCODED_QUALIFIER 11) COLUMN_QUALIFIER_COUNTER ('0'=8)";
    try {
      conn.createStatement().execute(ddl);
      fail("Invalid Column Qualifier");
    } catch (SQLException e) {
      // expected INVALID_CQ
      if (e.getErrorCode() != SQLExceptionCode.INVALID_CQ.getErrorCode()) {
        fail("Invalid Column Qualifier");
      }
    }

    ddl = "CREATE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT INTEGER ENCODED_QUALIFIER 15) COLUMN_QUALIFIER_COUNTER ('0'=13)";
    try {
      conn.createStatement().execute(ddl);
      fail("Invalid Column Qualifier");
    } catch (SQLException e) {
      // expected INVALID_CQ
      if (e.getErrorCode() != SQLExceptionCode.INVALID_CQ.getErrorCode()) {
        fail("Invalid Column Qualifier");
      }
    }

    ddl = "CREATE IMMUTABLE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT1 INTEGER, " + "INT2 INTEGER " + "DEFAULT_COLUMN_FAMILY=dF"
      + "COLUMN_QUALIFIER_COUNTER (\"0\"=13)";
    try {
      conn.createStatement().execute(ddl);
    } catch (SQLException e) {
      // expected
    }
  }

  @Test
  public void testCreateTableMissingColumnQualifier() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl = "CREATE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT INTEGER, INT2 INTEGER ENCODED_QUALIFIER 12)";
    try {
      conn.createStatement().execute(ddl);
      fail("Invalid Column Qualifier");
    } catch (SQLException e) {
      // expected MISSING_CQ
      if (e.getErrorCode() != SQLExceptionCode.MISSING_CQ.getErrorCode()) {
        fail("Missing Column Qualifier");
      }
    }

    ddl = "CREATE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT INTEGER ENCODED_QUALIFIER 11, INT2 INTEGER) COLUMN_QUALIFIER_COUNTER ('0'=13)";
    try {
      conn.createStatement().execute(ddl);
      fail("Invalid Column Qualifier");
    } catch (SQLException e) {
      // expected MISSING_CQ
      if (e.getErrorCode() != SQLExceptionCode.MISSING_CQ.getErrorCode()) {
        fail("Missing Column Qualifier");
      }
    }
  }

  @Test
  public void testCreateTableDefaultColumnQualifier() throws Exception {
    Properties props = new Properties();
    Connection conn = DriverManager.getConnection(getUrl(), props);
    String tableName = generateUniqueName();
    String ddl = "CREATE IMMUTABLE TABLE \"" + tableName + "\"(K VARCHAR NOT NULL PRIMARY KEY, "
      + "INT1 INTEGER, " + "INT2 INTEGER, " + "a.INT3 INTEGER, " + "\"A\".INT4 INTEGER, "
      + "\"b\".INT5 INTEGER, " + "\"B\".INT6 INTEGER) " + "DEFAULT_COLUMN_FAMILY=dF";
    conn.createStatement().execute(ddl);
    PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
    PTable table = pconn.getTable(new PTableKey(null, tableName));

    QualifierEncodingScheme encodingScheme = table.getEncodingScheme();
    assertNotEquals(PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS, encodingScheme);

    PTable.EncodedCQCounter cqCounter = table.getEncodedCQCounter();
    assertEquals(13, cqCounter.values().get("dF").intValue());
    assertEquals(13, cqCounter.values().get("A").intValue());
    assertEquals(12, cqCounter.values().get("b").intValue());
    assertEquals(12, cqCounter.values().get("B").intValue());
    assertEquals(11,
      encodingScheme.decode(table.getColumnForColumnName("INT1").getColumnQualifierBytes()));
    assertEquals(12,
      encodingScheme.decode(table.getColumnForColumnName("INT2").getColumnQualifierBytes()));
    assertEquals(11,
      encodingScheme.decode(table.getColumnForColumnName("INT3").getColumnQualifierBytes()));
    assertEquals(12,
      encodingScheme.decode(table.getColumnForColumnName("INT4").getColumnQualifierBytes()));
    assertEquals(11,
      encodingScheme.decode(table.getColumnForColumnName("INT5").getColumnQualifierBytes()));
    assertEquals(11,
      encodingScheme.decode(table.getColumnForColumnName("INT6").getColumnQualifierBytes()));
  }

  // Test for PHOENIX-6953
  @Test
  public void testCoprocessorsForCreateIndexOnOldImplementation() throws Exception {
    String tableName = generateUniqueName();
    String index1Name = generateUniqueName();
    String index2Name = generateUniqueName();

    String ddl =
      "create table  " + tableName + " ( k integer PRIMARY KEY," + " v1 integer," + " v2 integer)";
    String index1Ddl = "create index  " + index1Name + " on " + tableName + " (v1)";
    String index2Ddl = "create index  " + index2Name + " on " + tableName + " (v2)";

    Properties props = new Properties();
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement();) {
      stmt.execute(ddl);
      stmt.execute(index1Ddl);

      TableDescriptor index1DescriptorBefore = admin.getDescriptor(TableName.valueOf(index1Name));
      assertTrue(index1DescriptorBefore
        .hasCoprocessor(org.apache.phoenix.index.GlobalIndexChecker.class.getName()));

      // Now roll back to the old indexing
      IndexUpgradeTool iut = new IndexUpgradeTool(ROLLBACK_OP, tableName, null,
        File.createTempFile("index_upgrade_", null).toString(), false, null, false);
      iut.setConf(getUtility().getConfiguration());
      iut.prepareToolSetup();
      assertEquals(0, iut.executeTool());

      TableDescriptor index1DescriptorAfter = admin.getDescriptor(TableName.valueOf(index1Name));
      assertFalse(index1DescriptorAfter
        .hasCoprocessor(org.apache.phoenix.index.GlobalIndexChecker.class.getName()));

      // New index must not have GlobalIndexChecker
      stmt.execute(index2Ddl);
      TableDescriptor index2Descriptor = admin.getDescriptor(TableName.valueOf(index2Name));
      assertFalse(index2Descriptor
        .hasCoprocessor(org.apache.phoenix.index.GlobalIndexChecker.class.getName()));
    }
  }

  // Test for PHOENIX-6953
  @Test
  public void testCoprocessorsForTransactionalCreateIndexOnOldImplementation() throws Exception {
    String tableName = generateUniqueName();
    String index1Name = generateUniqueName();

    String ddl = "create table  " + tableName + " ( k integer PRIMARY KEY," + " v1 integer,"
      + " v2 integer) TRANSACTIONAL=TRUE";
    String index1Ddl = "create index  " + index1Name + " on " + tableName + " (v1)";

    Properties props = new Properties();
    Admin admin = driver.getConnectionQueryServices(getUrl(), props).getAdmin();

    try (Connection conn = DriverManager.getConnection(getUrl());
      Statement stmt = conn.createStatement();) {
      stmt.execute(ddl);
      stmt.execute(index1Ddl);

      // Transactional indexes don't have GlobalIndexChecker
      TableDescriptor index1DescriptorBefore = admin.getDescriptor(TableName.valueOf(index1Name));
      assertFalse(index1DescriptorBefore
        .hasCoprocessor(org.apache.phoenix.index.GlobalIndexChecker.class.getName()));

      // Now roll back to the old indexing
      IndexUpgradeTool iut = new IndexUpgradeTool(ROLLBACK_OP, tableName, null,
        File.createTempFile("index_upgrade_", null).toString(), false, null, false);
      iut.setConf(getUtility().getConfiguration());
      iut.prepareToolSetup();
      assertEquals(0, iut.executeTool());

      // Make sure we don't add GlobalIndexChecker
      TableDescriptor index1DescriptorAfter = admin.getDescriptor(TableName.valueOf(index1Name));
      assertFalse(index1DescriptorAfter
        .hasCoprocessor(org.apache.phoenix.index.GlobalIndexChecker.class.getName()));

      // We should also test for setting / unsetting the transactional status, but both are
      // forbidden at the time of writing.
    }
  }

  @Test
  public void testCreateTableWithNoVerify() throws SQLException, IOException, InterruptedException {
    final String tableName = SchemaUtil.getTableName(generateUniqueName(), generateUniqueName());
    final byte[] tableBytes = tableName.getBytes();
    final byte[] familyName = Bytes.toBytes(SchemaUtil.normalizeIdentifier("0"));
    final byte[][] splits = new byte[][] { Bytes.toBytes(20), Bytes.toBytes(30) };

    try (Admin admin = driver
      .getConnectionQueryServices(getUrl(), PropertiesUtil.deepCopy(TEST_PROPERTIES)).getAdmin()) {
      admin.createTable(TableDescriptorBuilder.newBuilder(TableName.valueOf(tableBytes))
        .addColumnFamily(ColumnFamilyDescriptorBuilder.newBuilder(familyName)
          .setKeepDeletedCells(KeepDeletedCells.TRUE).build())
        .build(), splits);
    }

    final byte[] uintCol = Bytes.toBytes("UINT_COL");
    final byte[] ulongCol = Bytes.toBytes("ULONG_COL");
    final byte[] key_1 =
      ByteUtil.concat(Bytes.toBytes(20), Bytes.toBytes(200L), Bytes.toBytes("b"));
    final byte[] key_2 =
      ByteUtil.concat(Bytes.toBytes(40), Bytes.toBytes(400L), Bytes.toBytes("d"));
    final byte[] emptyColumnQualifier = Bytes.toBytes("_0");

    ConnectionQueryServices services =
      driver.getConnectionQueryServices(getUrl(), PropertiesUtil.deepCopy(TEST_PROPERTIES));
    try (Table hTable = services.getTable(tableBytes)) {
      // Insert rows using standard HBase mechanism with standard HBase "types"
      List<Row> mutations = new ArrayList<>();

      Put put = new Put(key_1);
      put.addColumn(familyName, uintCol, HConstants.LATEST_TIMESTAMP, Bytes.toBytes(5000));
      put.addColumn(familyName, ulongCol, HConstants.LATEST_TIMESTAMP, Bytes.toBytes(50000L));
      mutations.add(put);

      put = new Put(key_2);
      put.addColumn(familyName, uintCol, HConstants.LATEST_TIMESTAMP, Bytes.toBytes(4000));
      put.addColumn(familyName, ulongCol, HConstants.LATEST_TIMESTAMP, Bytes.toBytes(40000L));
      mutations.add(put);

      hTable.batch(mutations, null);

      Result result = hTable.get(new Get(key_1));
      assertFalse(result.isEmpty());

      result = hTable.get(new Get(key_2));
      assertFalse(result.isEmpty());
    }

    String ddl = "create table " + tableName + "   (uint_key unsigned_int not null,"
      + "    ulong_key unsigned_long not null," + "    string_key varchar not null,\n"
      + "    uint_col unsigned_int," + "    ulong_col unsigned_long"
      + "    CONSTRAINT pk PRIMARY KEY (uint_key, ulong_key, string_key)) noverify COLUMN_ENCODED_BYTES=NONE";

    try (Connection conn = DriverManager.getConnection(url)) {
      conn.createStatement().execute(ddl);
    }

    try (Table hTable = services.getTable(tableBytes)) {
      Result result = hTable.get(new Get(key_1));

      byte[] value = result.getValue(familyName, uintCol);
      assertEquals(5000, Bytes.toInt(value));
      value = result.getValue(familyName, ulongCol);
      assertEquals(50000L, Bytes.toLong(value));

      value = result.getValue(familyName, emptyColumnQualifier);
      assertNull(value);

      result = hTable.get(new Get(key_2));
      value = result.getValue(familyName, uintCol);
      assertEquals(4000, Bytes.toInt(value));
      value = result.getValue(familyName, ulongCol);
      assertEquals(40000L, Bytes.toLong(value));

      value = result.getValue(familyName, emptyColumnQualifier);
      assertNull(value);
    }
  }

  public static long verifyLastDDLTimestamp(String tableFullName, long startTS, Connection conn)
    throws SQLException {
    long endTS = EnvironmentEdgeManager.currentTimeMillis();
    // Now try the PTable API
    long ddlTimestamp = getLastDDLTimestamp(conn, tableFullName);
    assertTrue("PTable DDL Timestamp: " + ddlTimestamp + " not in the expected range: (" + startTS
      + ", " + endTS + ")", ddlTimestamp >= startTS && ddlTimestamp <= endTS);
    return ddlTimestamp;
  }

  public static long getLastDDLTimestamp(Connection conn, String tableFullName)
    throws SQLException {
    PTable table = conn.unwrap(PhoenixConnection.class).getTableNoCache(tableFullName);
    assertNotNull("PTable is null!", table);
    assertNotNull("DDL timestamp is null!", table.getLastDDLTimestamp());
    return table.getLastDDLTimestamp();
  }

  private int checkGuidePostWidth(String tableName) throws Exception {
    try (Connection conn = DriverManager.getConnection(getUrl())) {
      String query =
        "SELECT GUIDE_POSTS_WIDTH FROM SYSTEM.CATALOG WHERE TABLE_NAME = ? AND COLUMN_FAMILY IS NULL AND COLUMN_NAME IS NULL";
      PreparedStatement stmt = conn.prepareStatement(query);
      stmt.setString(1, tableName);
      ResultSet rs = stmt.executeQuery();
      assertTrue(rs.next());
      return rs.getInt(1);
    }
  }

}
