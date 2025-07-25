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
package org.apache.phoenix.index;

import static org.apache.phoenix.query.QueryServices.INDEX_MUTATE_BATCH_SIZE_THRESHOLD_ATTRIB;
import static org.apache.phoenix.schema.types.PDataType.TRUE_BYTES;

import java.sql.SQLException;
import java.util.List;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.cache.ServerCacheClient;
import org.apache.phoenix.cache.ServerCacheClient.ServerCache;
import org.apache.phoenix.compile.ScanRanges;
import org.apache.phoenix.coprocessorclient.BaseScannerRegionObserverConstants;
import org.apache.phoenix.coprocessorclient.MetaDataProtocol;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.join.MaxServerCacheSizeExceededException;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.ScanUtil;

public class IndexMetaDataCacheClient {

  private final ServerCacheClient serverCache;
  private PTable cacheUsingTable;

  /**
   * Construct client used to send index metadata to each region server for caching during batched
   * put for secondary index maintenance.
   * @param connection      the client connection
   * @param cacheUsingTable table ref to table that will use the cache during its scan
   */
  public IndexMetaDataCacheClient(PhoenixConnection connection, PTable cacheUsingTable) {
    serverCache = new ServerCacheClient(connection);
    this.cacheUsingTable = cacheUsingTable;
  }

  /**
   * Determines whether or not to use the IndexMetaDataCache to send the index metadata to the
   * region servers. The alternative is to just set the index metadata as an attribute on the
   * mutations.
   * @param mutations               the list of mutations that will be sent in a batch to server
   * @param indexMetaDataByteLength length in bytes of the index metadata cache
   */
  public static boolean useIndexMetadataCache(PhoenixConnection connection,
    List<? extends Mutation> mutations, int indexMetaDataByteLength) {
    ReadOnlyProps props = connection.getQueryServices().getProps();
    int threshold = props.getInt(INDEX_MUTATE_BATCH_SIZE_THRESHOLD_ATTRIB,
      QueryServicesOptions.DEFAULT_INDEX_MUTATE_BATCH_SIZE_THRESHOLD);
    return (indexMetaDataByteLength > ServerCacheClient.UUID_LENGTH
      && mutations.size() > threshold);
  }

  /**
   * Send the index metadata cahce to all region servers for regions that will handle the mutations.
   * @return client-side {@link ServerCache} representing the added index metadata cache
   * @throws MaxServerCacheSizeExceededException if size of hash cache exceeds max allowed size
   */
  public ServerCache addIndexMetadataCache(List<? extends Mutation> mutations,
    ImmutableBytesWritable ptr, byte[] txState) throws SQLException {
    /**
     * Serialize and compress hashCacheTable
     */
    return serverCache.addServerCache(ScanUtil.newScanRanges(mutations), ptr, txState,
      new IndexMetaDataCacheFactory(), cacheUsingTable);
  }

  /**
   * Send the index metadata cahce to all region servers for regions that will handle the mutations.
   * @param txState TODO
   * @return client-side {@link ServerCache} representing the added index metadata cache
   * @throws MaxServerCacheSizeExceededException if size of hash cache exceeds max allowed size
   */
  public ServerCache addIndexMetadataCache(ScanRanges ranges, ImmutableBytesWritable ptr,
    byte[] txState) throws SQLException {
    /**
     * Serialize and compress hashCacheTable
     */
    return serverCache.addServerCache(ranges, ptr, txState, new IndexMetaDataCacheFactory(),
      cacheUsingTable);
  }

  public static ServerCache setMetaDataOnMutations(PhoenixConnection connection, PTable table,
    List<? extends Mutation> mutations, ImmutableBytesWritable indexMetaDataPtr)
    throws SQLException {
    final byte[] tenantIdBytes;
    if (table.isMultiTenant()) {
      tenantIdBytes = connection.getTenantId() == null
        ? null
        : ScanUtil.getTenantIdBytes(table.getRowKeySchema(), table.getBucketNum() != null,
          connection.getTenantId(), table.getViewIndexId() != null);
    } else {
      tenantIdBytes = connection.getTenantId() == null ? null : connection.getTenantId().getBytes();
    }
    ServerCache cache = null;
    byte[] attribValue = null;
    byte[] uuidValue = null;
    byte[] txState = ByteUtil.EMPTY_BYTE_ARRAY;
    if (table.isTransactional()) {
      txState = connection.getMutationState().encodeTransaction();
    }
    boolean hasIndexMetaData = indexMetaDataPtr.getLength() > 0;
    if (hasIndexMetaData) {
      if (
        useIndexMetadataCache(connection, mutations, indexMetaDataPtr.getLength() + txState.length)
      ) {
        IndexMetaDataCacheClient client = new IndexMetaDataCacheClient(connection, table);
        cache = client.addIndexMetadataCache(mutations, indexMetaDataPtr, txState);
        uuidValue = cache.getId();
      } else {
        attribValue = ByteUtil.copyKeyBytesIfNecessary(indexMetaDataPtr);
        uuidValue = ServerCacheClient.generateId();
      }
    } else if (txState.length == 0) {
      return null;
    }
    // Either set the UUID to be able to access the index metadata from the cache
    // or set the index metadata directly on the Mutation
    for (Mutation mutation : mutations) {
      if (connection.getTenantId() != null) {
        mutation.setAttribute(PhoenixRuntime.TENANT_ID_ATTRIB, tenantIdBytes);
      }
      mutation.setAttribute(PhoenixIndexCodec.INDEX_UUID, uuidValue);
      if (table.getTransformingNewTable() != null) {
        boolean disabled = table.getTransformingNewTable().isIndexStateDisabled();
        if (!disabled) {
          mutation.setAttribute(BaseScannerRegionObserverConstants.DO_TRANSFORMING, TRUE_BYTES);
        }
      }
      ScanUtil.annotateMutationWithMetadataAttributes(table, mutation);
      if (attribValue != null) {
        mutation.setAttribute(PhoenixIndexCodec.INDEX_PROTO_MD, attribValue);
        mutation.setAttribute(BaseScannerRegionObserverConstants.CLIENT_VERSION,
          Bytes.toBytes(MetaDataProtocol.PHOENIX_VERSION));
        if (txState.length > 0) {
          mutation.setAttribute(BaseScannerRegionObserverConstants.TX_STATE, txState);
        }
      } else if (!hasIndexMetaData && txState.length > 0) {
        mutation.setAttribute(BaseScannerRegionObserverConstants.TX_STATE, txState);
      }
    }
    return cache;
  }
}
