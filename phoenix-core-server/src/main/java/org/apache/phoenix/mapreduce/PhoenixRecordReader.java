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
package org.apache.phoenix.mapreduce;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.StatementContext;
import org.apache.phoenix.coprocessorclient.BaseScannerRegionObserverConstants;
import org.apache.phoenix.iterate.ConcatResultIterator;
import org.apache.phoenix.iterate.LookAheadResultIterator;
import org.apache.phoenix.iterate.ParallelScanGrouper;
import org.apache.phoenix.iterate.PeekingResultIterator;
import org.apache.phoenix.iterate.ResultIterator;
import org.apache.phoenix.iterate.RoundRobinResultIterator;
import org.apache.phoenix.iterate.SequenceResultIterator;
import org.apache.phoenix.iterate.TableResultIterator;
import org.apache.phoenix.iterate.TableSnapshotResultIterator;
import org.apache.phoenix.jdbc.PhoenixResultSet;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.apache.phoenix.monitoring.ReadMetricQueue;
import org.apache.phoenix.monitoring.ScanMetricsHolder;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;
import org.apache.phoenix.thirdparty.com.google.common.base.Throwables;
import org.apache.phoenix.thirdparty.com.google.common.collect.Lists;

/**
 * {@link RecordReader} implementation that iterates over the the records.
 */
public class PhoenixRecordReader<T extends DBWritable> extends RecordReader<NullWritable, T> {

  private static final Logger LOGGER = LoggerFactory.getLogger(PhoenixRecordReader.class);
  protected final Configuration configuration;
  protected final QueryPlan queryPlan;
  private final ParallelScanGrouper scanGrouper;
  private NullWritable key = NullWritable.get();
  private T value = null;
  private Class<T> inputClass;
  private ResultIterator resultIterator = null;
  private PhoenixResultSet resultSet;

  PhoenixRecordReader(Class<T> inputClass, final Configuration configuration,
    final QueryPlan queryPlan, final ParallelScanGrouper scanGrouper) {
    Preconditions.checkNotNull(configuration);
    Preconditions.checkNotNull(queryPlan);
    Preconditions.checkNotNull(scanGrouper);
    this.inputClass = inputClass;
    this.configuration = configuration;
    this.queryPlan = queryPlan;
    this.scanGrouper = scanGrouper;
  }

  @Override
  public void close() throws IOException {
    if (resultIterator != null) {
      try {
        resultIterator.close();
      } catch (SQLException e) {
        LOGGER.error(" Error closing resultset.");
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public NullWritable getCurrentKey() throws IOException, InterruptedException {
    return key;
  }

  @Override
  public T getCurrentValue() throws IOException, InterruptedException {
    return value;
  }

  @Override
  public float getProgress() throws IOException, InterruptedException {
    return 0;
  }

  @Override
  public void initialize(InputSplit split, TaskAttemptContext context)
    throws IOException, InterruptedException {
    final PhoenixInputSplit pSplit = (PhoenixInputSplit) split;
    final List<Scan> scans = pSplit.getScans();
    try {
      LOGGER.info(
        "Generating iterators for " + scans.size() + " scans in keyrange: " + pSplit.getKeyRange());
      List<PeekingResultIterator> iterators = Lists.newArrayListWithExpectedSize(scans.size());
      StatementContext ctx = queryPlan.getContext();
      ReadMetricQueue readMetrics = ctx.getReadMetricsQueue();
      String tableName = queryPlan.getTableRef().getTable().getPhysicalName().getString();
      String snapshotName = this.configuration.get(PhoenixConfigurationUtil.SNAPSHOT_NAME_KEY);

      // Clear the table region boundary cache to make sure long running jobs stay up to date
      byte[] tableNameBytes = queryPlan.getTableRef().getTable().getPhysicalName().getBytes();
      ConnectionQueryServices services = queryPlan.getContext().getConnection().getQueryServices();
      services.clearTableRegionCache(TableName.valueOf(tableNameBytes));

      long renewScannerLeaseThreshold = queryPlan.getContext().getConnection().getQueryServices()
        .getRenewLeaseThresholdMilliSeconds();
      for (Scan scan : scans) {
        // For MR, skip the region boundary check exception if we encounter a split. ref:
        // PHOENIX-2599
        scan.setAttribute(BaseScannerRegionObserverConstants.SKIP_REGION_BOUNDARY_CHECK,
          Bytes.toBytes(true));

        // Get QueryTimeout From Statement
        final long startTime = EnvironmentEdgeManager.currentTimeMillis();
        final long maxQueryEndTime =
          startTime + queryPlan.getContext().getStatement().getQueryTimeoutInMillis();
        PeekingResultIterator peekingResultIterator;
        ScanMetricsHolder scanMetricsHolder = ScanMetricsHolder.getInstance(readMetrics, tableName,
          scan, queryPlan.getContext().getConnection().getLogLevel());
        if (snapshotName != null) {
          // result iterator to read snapshots
          final TableSnapshotResultIterator tableSnapshotResultIterator =
            new TableSnapshotResultIterator(configuration, scan, scanMetricsHolder,
              queryPlan.getContext(), true, maxQueryEndTime);
          peekingResultIterator = LookAheadResultIterator.wrap(tableSnapshotResultIterator);
          LOGGER.info("Adding TableSnapshotResultIterator for scan: " + scan);
        } else {
          final TableResultIterator tableResultIterator = new TableResultIterator(
            queryPlan.getContext().getConnection().getMutationState(), scan, scanMetricsHolder,
            renewScannerLeaseThreshold, queryPlan, this.scanGrouper, true, maxQueryEndTime);
          peekingResultIterator = LookAheadResultIterator.wrap(tableResultIterator);
          LOGGER.info("Adding TableResultIterator for scan: " + scan);
        }
        iterators.add(peekingResultIterator);
      }
      ResultIterator iterator = queryPlan.useRoundRobinIterator()
        ? RoundRobinResultIterator.newIterator(iterators, queryPlan)
        : ConcatResultIterator.newIterator(iterators);
      if (queryPlan.getContext().getSequenceManager().getSequenceCount() > 0) {
        iterator =
          new SequenceResultIterator(iterator, queryPlan.getContext().getSequenceManager());
      }
      this.resultIterator = iterator;
      // Clone the row projector as it's not thread safe and would be used simultaneously by
      // multiple threads otherwise.

      this.resultSet = new PhoenixResultSet(this.resultIterator,
        queryPlan.getProjector().cloneIfNecessary(), queryPlan.getContext());
    } catch (SQLException e) {
      LOGGER.error(String.format(" Error [%s] initializing PhoenixRecordReader. ", e.getMessage()));
      Throwables.propagate(e);
    }
  }

  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    if (key == null) {
      key = NullWritable.get();
    }
    if (value == null) {
      value = ReflectionUtils.newInstance(inputClass, this.configuration);
    }
    Preconditions.checkNotNull(this.resultSet);
    try {
      if (!resultSet.next()) {
        return false;
      }
      value.readFields(resultSet);
      return true;
    } catch (SQLException e) {
      LOGGER.error(
        String.format(" Error [%s] occurred while iterating over the resultset. ", e.getMessage()));
      throw new RuntimeException(e);
    }
  }

}
