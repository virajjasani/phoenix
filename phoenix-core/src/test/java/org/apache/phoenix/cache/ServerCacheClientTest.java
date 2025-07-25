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
package org.apache.phoenix.cache;

import static org.junit.Assert.assertEquals;

import java.sql.SQLException;
import java.util.HashMap;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.schema.PNameFactory;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.Test;
import org.mockito.Mockito;

public class ServerCacheClientTest {
  @Test
  public void testAddServerCache() throws SQLException {
    PhoenixConnection connection = Mockito.mock(PhoenixConnection.class);
    ConnectionQueryServices services = Mockito.mock(ConnectionQueryServices.class);
    Mockito.when(services.getExecutor()).thenReturn(null);
    Mockito.when(services.getProps()).thenReturn(new ReadOnlyProps(new HashMap<>()));
    Mockito.when(connection.getQueryServices()).thenReturn(services);
    byte[] tableName = Bytes.toBytes("TableName");
    PTableImpl pTable = Mockito.mock(PTableImpl.class);
    Mockito.when(pTable.getPhysicalName()).thenReturn(PNameFactory.newName("TableName"));
    Mockito.when(services.getAllTableRegions(tableName, 600000))
      .thenThrow(new SQLException("Test Exception"));
    ServerCacheClient client = new ServerCacheClient(connection);
    try {
      client.addServerCache(null, null, null, null, pTable, false);
    } catch (Exception e) {
      assertEquals(e.getMessage(), "Test Exception");
    }
  }
}
