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
package org.apache.phoenix.schema;

import static org.apache.phoenix.schema.PTableImpl.getColumnsToClone;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.apache.hadoop.hbase.HConstants;
import org.apache.phoenix.monitoring.GlobalClientMetrics;
import org.apache.phoenix.parse.PFunction;
import org.apache.phoenix.parse.PSchema;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.TimeKeeper;

import org.apache.phoenix.thirdparty.com.google.common.base.Strings;
import org.apache.phoenix.thirdparty.com.google.common.collect.Lists;

/**
 * Client-side cache of MetaData, not thread safe. Internally uses a LinkedHashMap that evicts the
 * oldest entries when size grows beyond the maxSize specified at create time.
 */
public class PMetaDataImpl implements PMetaData {

  private PMetaDataCache metaData;
  private final TimeKeeper timeKeeper;
  private final PTableRefFactory tableRefFactory;
  private final long updateCacheFrequency;
  private HashMap<String, PTableKey> physicalNameToLogicalTableMap = new HashMap<>();

  public PMetaDataImpl(int initialCapacity, long updateCacheFrequency, ReadOnlyProps props) {
    this(initialCapacity, updateCacheFrequency, TimeKeeper.SYSTEM, props);
  }

  public PMetaDataImpl(int initialCapacity, long updateCacheFrequency, TimeKeeper timeKeeper,
    ReadOnlyProps props) {

    this(
      new PMetaDataCache(initialCapacity,
        props.getLong(QueryServices.MAX_CLIENT_METADATA_CACHE_SIZE_ATTRIB,
          QueryServicesOptions.DEFAULT_MAX_CLIENT_METADATA_CACHE_SIZE),
        timeKeeper),
      timeKeeper, PTableRefFactory.getFactory(props), updateCacheFrequency);
  }

  private PMetaDataImpl(PMetaDataCache metaData, TimeKeeper timeKeeper,
    PTableRefFactory tableRefFactory, long updateCacheFrequency) {
    this.timeKeeper = timeKeeper;
    this.metaData = metaData;
    this.tableRefFactory = tableRefFactory;
    this.updateCacheFrequency = updateCacheFrequency;
  }

  private void updateGlobalMetric(PTableRef pTableRef) {
    if (pTableRef != null && pTableRef.getTable() != null) {
      GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_HIT_COUNTER.increment();
    } else {
      GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_MISS_COUNTER.increment();
    }
  }

  @Override
  public PTableRef getTableRef(PTableKey key) throws TableNotFoundException {
    if (physicalNameToLogicalTableMap.containsKey(key.getName())) {
      key = physicalNameToLogicalTableMap.get(key.getName());
    }
    PTableRef ref = metaData.get(key);
    if (!key.getName().contains(QueryConstants.SYSTEM_SCHEMA_NAME)) {
      updateGlobalMetric(ref);
    }
    if (ref == null) {
      throw new TableNotFoundException(key.getName());
    }
    return ref;
  }

  @Override
  public PFunction getFunction(PTableKey key) throws FunctionNotFoundException {
    PFunction function = metaData.functions.get(key);
    if (function == null) {
      throw new FunctionNotFoundException(key.getName());
    }
    return function;
  }

  @Override
  public int size() {
    return (int) metaData.size();
  }

  // TODO The tables with zero update cache frequency should not be inserted to the cache. However,
  // Phoenix
  // uses the cache as the temporary memory during all operations currently. When this behavior
  // changes, we can use
  // useMetaDataCache to determine if a table should be inserted to the cache.
  private boolean useMetaDataCache(PTable table) {
    return table.getType() == PTableType.SYSTEM || table.getUpdateCacheFrequency() != 0
      || updateCacheFrequency != 0;
  }

  @Override
  public void updateResolvedTimestamp(PTable table, long resolvedTimestamp) throws SQLException {
    metaData.put(table.getKey(),
      tableRefFactory.makePTableRef(table, this.timeKeeper.getCurrentTime(), resolvedTimestamp));
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ADD_COUNTER.increment();
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ESTIMATED_USED_SIZE
      .update(table.getEstimatedSize());
  }

  @Override
  public void addTable(PTable table, long resolvedTime) throws SQLException {
    PTableRef tableRef =
      tableRefFactory.makePTableRef(table, this.timeKeeper.getCurrentTime(), resolvedTime);
    PTableKey key = table.getKey();
    PTable newParentTable = null;
    PTableRef newParentTableRef = null;
    long parentResolvedTimestamp = resolvedTime;
    if (table.getType() == PTableType.INDEX) { // Upsert new index table into parent data table list
      String parentName = table.getParentName().getString();
      PTableRef oldParentRef = metaData.get(new PTableKey(table.getTenantId(), parentName));
      // If parentTable isn't cached, that's ok we can skip this
      if (oldParentRef != null) {
        List<PTable> oldIndexes = oldParentRef.getTable().getIndexes();
        List<PTable> newIndexes = Lists.newArrayListWithExpectedSize(oldIndexes.size() + 1);
        newIndexes.addAll(oldIndexes);
        for (int i = 0; i < newIndexes.size(); i++) {
          PTable index = newIndexes.get(i);
          if (index.getName().equals(table.getName())) {
            newIndexes.remove(i);
            break;
          }
        }
        newIndexes.add(table);
        newParentTable = PTableImpl
          .builderWithColumns(oldParentRef.getTable(), getColumnsToClone(oldParentRef.getTable()))
          .setIndexes(newIndexes).setTimeStamp(table.getTimeStamp()).build();
        newParentTableRef = tableRefFactory.makePTableRef(newParentTable,
          this.timeKeeper.getCurrentTime(), parentResolvedTimestamp);
      }
    }

    if (newParentTable != null) { // Upsert new index table into parent data table list
      metaData.put(newParentTable.getKey(), newParentTableRef);
      GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ADD_COUNTER.increment();
      GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ESTIMATED_USED_SIZE
        .update(newParentTable.getEstimatedSize());
    }
    metaData.put(table.getKey(), tableRef);
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ADD_COUNTER.increment();
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ESTIMATED_USED_SIZE
      .update(table.getEstimatedSize());

    for (PTable index : table.getIndexes()) {
      metaData.put(index.getKey(),
        tableRefFactory.makePTableRef(index, this.timeKeeper.getCurrentTime(), resolvedTime));
      GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ADD_COUNTER.increment();
      GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ESTIMATED_USED_SIZE
        .update(index.getEstimatedSize());
    }
    if (
      table.getPhysicalName(true) != null
        && !Strings.isNullOrEmpty(table.getPhysicalName(true).getString())
        && !table.getPhysicalName(true).getString().equals(table.getTableName().getString())
    ) {
      String physicalTableName = table.getPhysicalName(true).getString()
        .replace(QueryConstants.NAMESPACE_SEPARATOR, QueryConstants.NAME_SEPARATOR);
      String physicalTableFullName = SchemaUtil.getTableName(
        table.getSchemaName() != null ? table.getSchemaName().getString() : null,
        physicalTableName);
      this.physicalNameToLogicalTableMap.put(physicalTableFullName, key);
    }
  }

  @Override
  public void removeTable(PName tenantId, String tableName, String parentTableName,
    long tableTimeStamp) throws SQLException {
    PTableRef parentTableRef = null;
    PTableKey key = new PTableKey(tenantId, tableName);
    if (metaData.get(key) == null) {
      if (parentTableName != null) {
        parentTableRef = metaData.get(new PTableKey(tenantId, parentTableName));
      }
      if (parentTableRef == null) {
        return;
      }
    } else {
      PTable table = metaData.remove(key);
      for (PTable index : table.getIndexes()) {
        metaData.remove(index.getKey());
      }
      if (table.getParentName() != null) {
        parentTableRef = metaData.get(new PTableKey(tenantId, table.getParentName().getString()));
      }
    }
    // also remove its reference from parent table
    if (parentTableRef != null) {
      List<PTable> oldIndexes = parentTableRef.getTable().getIndexes();
      if (oldIndexes != null && !oldIndexes.isEmpty()) {
        List<PTable> newIndexes = Lists.newArrayListWithExpectedSize(oldIndexes.size());
        newIndexes.addAll(oldIndexes);
        for (int i = 0; i < newIndexes.size(); i++) {
          PTable index = newIndexes.get(i);
          if (index.getName().getString().equals(tableName)) {
            newIndexes.remove(i);
            PTableImpl.Builder parentTableBuilder =
              PTableImpl.builderWithColumns(parentTableRef.getTable(),
                getColumnsToClone(parentTableRef.getTable())).setIndexes(newIndexes);
            if (tableTimeStamp != HConstants.LATEST_TIMESTAMP) {
              parentTableBuilder.setTimeStamp(tableTimeStamp);
            }
            PTable parentTable = parentTableBuilder.build();
            metaData.put(parentTable.getKey(), tableRefFactory.makePTableRef(parentTable,
              this.timeKeeper.getCurrentTime(), parentTableRef.getResolvedTimeStamp()));
            GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ADD_COUNTER.increment();
            GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ESTIMATED_USED_SIZE
              .update(parentTable.getEstimatedSize());
            break;
          }
        }
      }
    }
  }

  @Override
  public void removeColumn(PName tenantId, String tableName, List<PColumn> columnsToRemove,
    long tableTimeStamp, long tableSeqNum, long resolvedTime) throws SQLException {
    PTableRef tableRef = metaData.get(new PTableKey(tenantId, tableName));
    if (tableRef == null) {
      return;
    }
    PTable table = tableRef.getTable();
    PMetaDataCache tables = metaData;
    for (PColumn columnToRemove : columnsToRemove) {
      PColumn column;
      String familyName = columnToRemove.getFamilyName().getString();
      if (familyName == null) {
        column = table.getPKColumn(columnToRemove.getName().getString());
      } else {
        column = table.getColumnFamily(familyName)
          .getPColumnForColumnName(columnToRemove.getName().getString());
      }
      int positionOffset = 0;
      int position = column.getPosition();
      List<PColumn> oldColumns = table.getColumns();
      if (table.getBucketNum() != null) {
        position--;
        positionOffset = 1;
        oldColumns = oldColumns.subList(positionOffset, oldColumns.size());
      }
      List<PColumn> columns = Lists.newArrayListWithExpectedSize(oldColumns.size() - 1);
      columns.addAll(oldColumns.subList(0, position));
      // Update position of columns that follow removed column
      for (int i = position + 1; i < oldColumns.size(); i++) {
        PColumn oldColumn = oldColumns.get(i);
        PColumn newColumn = new PColumnImpl(oldColumn.getName(), oldColumn.getFamilyName(),
          oldColumn.getDataType(), oldColumn.getMaxLength(), oldColumn.getScale(),
          oldColumn.isNullable(), i - 1 + positionOffset, oldColumn.getSortOrder(),
          oldColumn.getArraySize(), oldColumn.getViewConstant(), oldColumn.isViewReferenced(),
          oldColumn.getExpressionStr(), oldColumn.isRowTimestamp(), oldColumn.isDynamic(),
          oldColumn.getColumnQualifierBytes(), oldColumn.getTimestamp());
        columns.add(newColumn);
      }
      table = PTableImpl.builderWithColumns(table, columns).setTimeStamp(tableTimeStamp)
        .setSequenceNumber(tableSeqNum).build();
    }
    tables.put(table.getKey(),
      tableRefFactory.makePTableRef(table, this.timeKeeper.getCurrentTime(), resolvedTime));
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ADD_COUNTER.increment();
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ESTIMATED_USED_SIZE
      .update(table.getEstimatedSize());
  }

  @Override
  public void pruneTables(Pruner pruner) {
    List<PTableKey> keysToPrune = Lists.newArrayListWithExpectedSize(this.size());
    for (PTable table : this) {
      if (pruner.prune(table)) {
        keysToPrune.add(table.getKey());
      }
    }
    if (!keysToPrune.isEmpty()) {
      for (PTableKey key : keysToPrune) {
        metaData.remove(key);
      }
    }
  }

  @Override
  public Iterator<PTable> iterator() {
    return metaData.iterator();
  }

  @Override
  public void addFunction(PFunction function) throws SQLException {
    this.metaData.functions.put(function.getKey(), function);
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ADD_COUNTER.increment();
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ESTIMATED_USED_SIZE
      .update(function.getEstimatedSize());
  }

  @Override
  public void removeFunction(PName tenantId, String function, long functionTimeStamp)
    throws SQLException {
    this.metaData.functions.remove(new PTableKey(tenantId, function));
  }

  @Override
  public void pruneFunctions(Pruner pruner) {
    List<PTableKey> keysToPrune = Lists.newArrayListWithExpectedSize(this.size());
    for (PFunction function : this.metaData.functions.values()) {
      if (pruner.prune(function)) {
        keysToPrune.add(function.getKey());
      }
    }
    if (!keysToPrune.isEmpty()) {
      for (PTableKey key : keysToPrune) {
        metaData.functions.remove(key);
      }
    }
  }

  @Override
  public long getAge(PTableRef ref) {
    return this.metaData.getAge(ref);
  }

  @Override
  public void addSchema(PSchema schema) throws SQLException {
    this.metaData.schemas.put(schema.getSchemaKey(), schema);
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ADD_COUNTER.increment();
    GlobalClientMetrics.GLOBAL_CLIENT_METADATA_CACHE_ESTIMATED_USED_SIZE
      .update(schema.getEstimatedSize());
  }

  @Override
  public PSchema getSchema(PTableKey key) throws SchemaNotFoundException {
    PSchema schema = metaData.schemas.get(key);
    if (schema == null) {
      throw new SchemaNotFoundException(key.getName());
    }
    return schema;
  }

  @Override
  public void removeSchema(PSchema schema, long schemaTimeStamp) {
    this.metaData.schemas.remove(schema.getSchemaKey());
  }

}
