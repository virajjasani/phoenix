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
package org.apache.phoenix.query;

import static org.apache.hadoop.hbase.HConstants.DEFAULT_HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD;
import static org.apache.phoenix.query.QueryServices.ALLOWED_LIST_FOR_TABLE_LEVEL_METRICS;
import static org.apache.phoenix.query.QueryServices.ALLOW_ONLINE_TABLE_SCHEMA_UPDATE;
import static org.apache.phoenix.query.QueryServices.ALLOW_VIEWS_ADD_NEW_CF_BASE_TABLE;
import static org.apache.phoenix.query.QueryServices.AUTO_UPGRADE_ENABLED;
import static org.apache.phoenix.query.QueryServices.CALL_QUEUE_PRODUCER_ATTRIB_NAME;
import static org.apache.phoenix.query.QueryServices.CALL_QUEUE_ROUND_ROBIN_ATTRIB;
import static org.apache.phoenix.query.QueryServices.CDC_TTL_MUTATION_BATCH_SIZE;
import static org.apache.phoenix.query.QueryServices.CDC_TTL_MUTATION_MAX_RETRIES;
import static org.apache.phoenix.query.QueryServices.CDC_TTL_SHARED_CACHE_EXPIRY_SECONDS;
import static org.apache.phoenix.query.QueryServices.CLIENT_INDEX_ASYNC_THRESHOLD;
import static org.apache.phoenix.query.QueryServices.CLIENT_METRICS_TAG;
import static org.apache.phoenix.query.QueryServices.CLIENT_SPOOL_THRESHOLD_BYTES_ATTRIB;
import static org.apache.phoenix.query.QueryServices.CLUSTER_ROLE_BASED_MUTATION_BLOCK_ENABLED;
import static org.apache.phoenix.query.QueryServices.COLLECT_REQUEST_LEVEL_METRICS;
import static org.apache.phoenix.query.QueryServices.COMMIT_STATS_ASYNC;
import static org.apache.phoenix.query.QueryServices.CONNECTION_ACTIVITY_LOGGING_ENABLED;
import static org.apache.phoenix.query.QueryServices.CONNECTION_ACTIVITY_LOGGING_INTERVAL;
import static org.apache.phoenix.query.QueryServices.CONNECTION_EXPLAIN_PLAN_LOGGING_ENABLED;
import static org.apache.phoenix.query.QueryServices.CONNECTION_QUERY_SERVICE_HISTOGRAM_SIZE_RANGES;
import static org.apache.phoenix.query.QueryServices.CONNECTION_QUERY_SERVICE_METRICS_ENABLED;
import static org.apache.phoenix.query.QueryServices.CONNECTION_QUERY_SERVICE_METRICS_PUBLISHER_CLASSNAME;
import static org.apache.phoenix.query.QueryServices.CONNECTION_QUERY_SERVICE_METRICS_PUBLISHER_ENABLED;
import static org.apache.phoenix.query.QueryServices.COST_BASED_OPTIMIZER_ENABLED;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_ALLOW_CORE_THREAD_TIMEOUT;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_CORE_POOL_SIZE;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_ENABLED;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_KEEP_ALIVE_SECONDS;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_MAX_QUEUE;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_MAX_THREADS;
import static org.apache.phoenix.query.QueryServices.CQSI_THREAD_POOL_METRICS_ENABLED;
import static org.apache.phoenix.query.QueryServices.DATE_FORMAT_ATTRIB;
import static org.apache.phoenix.query.QueryServices.DATE_FORMAT_TIMEZONE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.DELAY_FOR_SCHEMA_UPDATE_CHECK;
import static org.apache.phoenix.query.QueryServices.DROP_METADATA_ATTRIB;
import static org.apache.phoenix.query.QueryServices.EXPLAIN_CHUNK_COUNT_ATTRIB;
import static org.apache.phoenix.query.QueryServices.EXPLAIN_ROW_COUNT_ATTRIB;
import static org.apache.phoenix.query.QueryServices.EXTRA_JDBC_ARGUMENTS_ATTRIB;
import static org.apache.phoenix.query.QueryServices.FORCE_ROW_KEY_ORDER_ATTRIB;
import static org.apache.phoenix.query.QueryServices.GLOBAL_METRICS_ENABLED;
import static org.apache.phoenix.query.QueryServices.GROUPBY_MAX_CACHE_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.GROUPBY_SPILLABLE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.GROUPBY_SPILL_FILES_ATTRIB;
import static org.apache.phoenix.query.QueryServices.HBASE_CLIENT_SCANNER_TIMEOUT_ATTRIB;
import static org.apache.phoenix.query.QueryServices.IMMUTABLE_ROWS_ATTRIB;
import static org.apache.phoenix.query.QueryServices.INDEX_CREATE_DEFAULT_STATE;
import static org.apache.phoenix.query.QueryServices.INDEX_MUTATE_BATCH_SIZE_THRESHOLD_ATTRIB;
import static org.apache.phoenix.query.QueryServices.INDEX_POPULATION_SLEEP_TIME;
import static org.apache.phoenix.query.QueryServices.INDEX_REBUILD_TASK_INITIAL_DELAY;
import static org.apache.phoenix.query.QueryServices.IS_NAMESPACE_MAPPING_ENABLED;
import static org.apache.phoenix.query.QueryServices.IS_SYSTEM_TABLE_MAPPED_TO_NAMESPACE;
import static org.apache.phoenix.query.QueryServices.KEEP_ALIVE_MS_ATTRIB;
import static org.apache.phoenix.query.QueryServices.LOCAL_INDEX_CLIENT_UPGRADE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.LOG_LEVEL;
import static org.apache.phoenix.query.QueryServices.LOG_SAMPLE_RATE;
import static org.apache.phoenix.query.QueryServices.MASTER_INFO_PORT_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_CLIENT_METADATA_CACHE_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_IN_LIST_SKIP_SCAN_SIZE;
import static org.apache.phoenix.query.QueryServices.MAX_MEMORY_PERC_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_MUTATION_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_REGION_LOCATIONS_SIZE_EXPLAIN_PLAN;
import static org.apache.phoenix.query.QueryServices.MAX_SERVER_CACHE_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_SERVER_CACHE_TIME_TO_LIVE_MS_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_SERVER_METADATA_CACHE_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_SPOOL_TO_DISK_BYTES_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_TENANT_MEMORY_PERC_ATTRIB;
import static org.apache.phoenix.query.QueryServices.METRIC_PUBLISHER_CLASS_NAME;
import static org.apache.phoenix.query.QueryServices.METRIC_PUBLISHER_ENABLED;
import static org.apache.phoenix.query.QueryServices.MIN_STATS_UPDATE_FREQ_MS_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MUTATE_BATCH_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.NUM_RETRIES_FOR_SCHEMA_UPDATE_CHECK;
import static org.apache.phoenix.query.QueryServices.PHOENIX_ACLS_ENABLED;
import static org.apache.phoenix.query.QueryServices.QUERY_SERVICES_NAME;
import static org.apache.phoenix.query.QueryServices.QUEUE_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.REGIONSERVER_INFO_PORT_ATTRIB;
import static org.apache.phoenix.query.QueryServices.RENEW_LEASE_ENABLED;
import static org.apache.phoenix.query.QueryServices.RENEW_LEASE_THREAD_POOL_SIZE;
import static org.apache.phoenix.query.QueryServices.RENEW_LEASE_THRESHOLD_MILLISECONDS;
import static org.apache.phoenix.query.QueryServices.ROW_KEY_ORDER_SALTED_TABLE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.RPC_TIMEOUT_ATTRIB;
import static org.apache.phoenix.query.QueryServices.RUN_RENEW_LEASE_FREQUENCY_INTERVAL_MILLISECONDS;
import static org.apache.phoenix.query.QueryServices.RUN_UPDATE_STATS_ASYNC;
import static org.apache.phoenix.query.QueryServices.SCAN_CACHE_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.SCAN_RESULT_CHUNK_SIZE;
import static org.apache.phoenix.query.QueryServices.SEQUENCE_CACHE_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.SEQUENCE_SALT_BUCKETS_ATTRIB;
import static org.apache.phoenix.query.QueryServices.SERVER_MERGE_FOR_UNCOVERED_INDEX;
import static org.apache.phoenix.query.QueryServices.SERVER_SPOOL_THRESHOLD_BYTES_ATTRIB;
import static org.apache.phoenix.query.QueryServices.SKIP_SYSTEM_TABLES_EXISTENCE_CHECK;
import static org.apache.phoenix.query.QueryServices.SPOOL_DIRECTORY;
import static org.apache.phoenix.query.QueryServices.STATS_CACHE_THREAD_POOL_SIZE;
import static org.apache.phoenix.query.QueryServices.STATS_COLLECTION_ENABLED;
import static org.apache.phoenix.query.QueryServices.STATS_GUIDEPOST_WIDTH_BYTES_ATTRIB;
import static org.apache.phoenix.query.QueryServices.STATS_UPDATE_FREQ_MS_ATTRIB;
import static org.apache.phoenix.query.QueryServices.STATS_USE_CURRENT_TIME_ATTRIB;
import static org.apache.phoenix.query.QueryServices.TABLE_LEVEL_METRICS_ENABLED;
import static org.apache.phoenix.query.QueryServices.THREAD_POOL_SIZE_ATTRIB;
import static org.apache.phoenix.query.QueryServices.THREAD_TIMEOUT_MS_ATTRIB;
import static org.apache.phoenix.query.QueryServices.TRACING_BATCH_SIZE;
import static org.apache.phoenix.query.QueryServices.TRACING_ENABLED;
import static org.apache.phoenix.query.QueryServices.TRACING_STATS_TABLE_NAME_ATTRIB;
import static org.apache.phoenix.query.QueryServices.TRACING_THREAD_POOL_SIZE;
import static org.apache.phoenix.query.QueryServices.TRACING_TRACE_BUFFER_SIZE;
import static org.apache.phoenix.query.QueryServices.TRANSACTIONS_ENABLED;
import static org.apache.phoenix.query.QueryServices.UPLOAD_BINARY_DATA_TYPE_ENCODING;
import static org.apache.phoenix.query.QueryServices.USE_BYTE_BASED_REGEX_ATTRIB;
import static org.apache.phoenix.query.QueryServices.USE_INDEXES_ATTRIB;
import static org.apache.phoenix.query.QueryServices.USE_STATS_FOR_PARALLELIZATION;
import static org.apache.phoenix.query.QueryServices.WAL_EDIT_CODEC_ATTRIB;

import java.util.Map.Entry;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.client.Consistency;
import org.apache.hadoop.hbase.ipc.RpcControllerFactory;
import org.apache.hadoop.hbase.ipc.controller.ClientRpcControllerFactory;
import org.apache.phoenix.log.LogLevel;
import org.apache.phoenix.schema.ConnectionProperty;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTable.ImmutableStorageScheme;
import org.apache.phoenix.schema.PTable.QualifierEncodingScheme;
import org.apache.phoenix.schema.PTableRefFactory;
import org.apache.phoenix.trace.util.Tracing;
import org.apache.phoenix.transaction.TransactionFactory;
import org.apache.phoenix.util.DateUtil;
import org.apache.phoenix.util.ReadOnlyProps;

import org.apache.hbase.thirdparty.com.google.common.annotations.VisibleForTesting;

/**
 * Options for {@link QueryServices}.
 * @since 0.1
 */
public class QueryServicesOptions {
  public static final int DEFAULT_KEEP_ALIVE_MS = 60000;
  public static final int DEFAULT_THREAD_POOL_SIZE = 128;
  public static final int DEFAULT_QUEUE_SIZE = 5000;
  public static final int UNLIMITED_QUEUE_SIZE = -1;
  public static final int DEFAULT_THREAD_TIMEOUT_MS = 600000; // 10min
  public static final int DEFAULT_SPOOL_THRESHOLD_BYTES = 1024 * 1024 * 20; // 20m
  public static final int DEFAULT_SERVER_SPOOL_THRESHOLD_BYTES = 1024 * 1024 * 20; // 20m
  public static final int DEFAULT_CLIENT_SPOOL_THRESHOLD_BYTES = 1024 * 1024 * 20; // 20m
  public static final boolean DEFAULT_CLIENT_ORDERBY_SPOOLING_ENABLED = true;
  public static final boolean DEFAULT_CLIENT_JOIN_SPOOLING_ENABLED = true;
  public static final boolean DEFAULT_SERVER_ORDERBY_SPOOLING_ENABLED = true;
  public static final String DEFAULT_SPOOL_DIRECTORY = System.getProperty("java.io.tmpdir");
  public static final int DEFAULT_MAX_MEMORY_PERC = 15; // 15% of heap
  public static final int DEFAULT_MAX_TENANT_MEMORY_PERC = 100;
  public static final long DEFAULT_MAX_SERVER_CACHE_SIZE = 1024 * 1024 * 100; // 100 Mb
  public static final int DEFAULT_TARGET_QUERY_CONCURRENCY = 32;
  public static final int DEFAULT_MAX_QUERY_CONCURRENCY = 64;
  public static final String DEFAULT_DATE_FORMAT = DateUtil.DEFAULT_DATE_FORMAT;
  public static final String DEFAULT_DATE_FORMAT_TIMEZONE = DateUtil.DEFAULT_TIME_ZONE_ID;
  public static final boolean DEFAULT_CALL_QUEUE_ROUND_ROBIN = true;
  public static final int DEFAULT_MAX_MUTATION_SIZE = 500000;
  public static final int DEFAULT_MAX_MUTATION_SIZE_BYTES = 104857600; // 100 Mb
  public static final int DEFAULT_HBASE_CLIENT_KEYVALUE_MAXSIZE = 10485760; // 10 Mb
  public static final boolean DEFAULT_USE_INDEXES = true; // Use indexes
  public static final boolean DEFAULT_IMMUTABLE_ROWS = false; // Tables rows may be updated
  public static final boolean DEFAULT_DROP_METADATA = true; // Drop meta data also.
  public static final long DEFAULT_DRIVER_SHUTDOWN_TIMEOUT_MS = 5 * 1000; // Time to wait in
                                                                          // ShutdownHook to exit
                                                                          // gracefully.
  public static final boolean DEFAULT_TRACING_ENABLED = false;
  public static final int DEFAULT_TRACING_THREAD_POOL_SIZE = 5;
  public static final int DEFAULT_TRACING_BATCH_SIZE = 100;
  public static final int DEFAULT_TRACING_TRACE_BUFFER_SIZE = 1000;
  public static final int DEFAULT_MAX_INDEXES_PER_TABLE = 10;
  public static final int DEFAULT_CLIENT_INDEX_ASYNC_THRESHOLD = 0;

  public final static int DEFAULT_MUTATE_BATCH_SIZE = 100; // Batch size for UPSERT SELECT and
                                                           // DELETE
  // Batch size in bytes for UPSERT, SELECT and DELETE. By default, 2MB
  public final static long DEFAULT_MUTATE_BATCH_SIZE_BYTES = 2097152;
  // The only downside of it being out-of-sync is that the parallelization of the scan won't be as
  // balanced as it could be.
  public static final int DEFAULT_MAX_SERVER_CACHE_TIME_TO_LIVE_MS = 30000; // 30 sec (with no
                                                                            // activity)
  public static final int DEFAULT_MAX_SERVER_CACHE_PERSISTENCE_TIME_TO_LIVE_MS = 30 * 60000; // 30
                                                                                             // minutes
  public static final int DEFAULT_SCAN_CACHE_SIZE = 1000;
  public static final int DEFAULT_MAX_INTRA_REGION_PARALLELIZATION = DEFAULT_MAX_QUERY_CONCURRENCY;
  public static final int DEFAULT_DISTINCT_VALUE_COMPRESS_THRESHOLD = 1024 * 1024 * 1; // 1 Mb
  public static final int DEFAULT_AGGREGATE_CHUNK_SIZE_INCREASE = 1024 * 1024 * 1; // 1 Mb
  public static final int DEFAULT_INDEX_MUTATE_BATCH_SIZE_THRESHOLD = 3;
  public static final long DEFAULT_MAX_SPOOL_TO_DISK_BYTES = 1024000000;
  // Only the first chunked batches are fetched in parallel, so this default
  // should be on the relatively bigger side of things. Bigger means more
  // latency and client-side spooling/buffering. Smaller means less initial
  // latency and less parallelization.
  public static final long DEFAULT_SCAN_RESULT_CHUNK_SIZE = 2999;
  public static final boolean DEFAULT_IS_NAMESPACE_MAPPING_ENABLED = false;
  public static final boolean DEFAULT_IS_SYSTEM_TABLE_MAPPED_TO_NAMESPACE = true;
  public static final int DEFAULT_MAX_IN_LIST_SKIP_SCAN_SIZE = 50000;

  //
  // Spillable GroupBy - SPGBY prefix
  //
  // Enable / disable spillable group by
  public static final boolean DEFAULT_GROUPBY_SPILLABLE = true;
  // Number of spill files / partitions the keys are distributed to
  // Each spill file fits 2GB of data
  public static final int DEFAULT_GROUPBY_SPILL_FILES = 2;
  // Max size of 1st level main memory cache in bytes --> upper bound
  public static final long DEFAULT_GROUPBY_MAX_CACHE_MAX = 1024L * 1024L * 100L; // 100 Mb

  public static final long DEFAULT_SEQUENCE_CACHE_SIZE = 100; // reserve 100 sequences at a time
  public static final int GLOBAL_INDEX_CHECKER_ENABLED_MAP_EXPIRATION_MIN = 10;
  public static final long DEFAULT_MAX_SERVER_METADATA_CACHE_TIME_TO_LIVE_MS = 60000 * 30; // 30
                                                                                           // mins
  public static final long DEFAULT_MAX_SERVER_METADATA_CACHE_SIZE = 1024L * 1024L * 20L; // 20 Mb
  public static final long DEFAULT_MAX_CLIENT_METADATA_CACHE_SIZE = 1024L * 1024L * 10L; // 10 Mb
  public static final int DEFAULT_GROUPBY_ESTIMATED_DISTINCT_VALUES = 1000;
  public static final int DEFAULT_CLOCK_SKEW_INTERVAL = 2000;
  public static final boolean DEFAULT_INDEX_FAILURE_HANDLING_REBUILD = true; // auto rebuild on
  public static final boolean DEFAULT_INDEX_FAILURE_BLOCK_WRITE = false;
  public static final boolean DEFAULT_INDEX_FAILURE_DISABLE_INDEX = true;
  public static final boolean DEFAULT_INDEX_FAILURE_THROW_EXCEPTION = true;
  public static final long DEFAULT_INDEX_FAILURE_HANDLING_REBUILD_INTERVAL = 60000; // 60 secs
  public static final long DEFAULT_INDEX_REBUILD_TASK_INITIAL_DELAY = 10000; // 10 secs
  public static final long DEFAULT_START_TRUNCATE_TASK_DELAY = 20000; // 20 secs
  public static final long DEFAULT_INDEX_FAILURE_HANDLING_REBUILD_OVERLAP_BACKWARD_TIME = 1; // 1 ms
  public static final long DEFAULT_INDEX_FAILURE_HANDLING_REBUILD_OVERLAP_FORWARD_TIME = 60000 * 3; // 3
                                                                                                    // mins
  // 30 min rpc timeout * 5 tries, with 2100ms total pause time between retries
  public static final long DEFAULT_INDEX_REBUILD_QUERY_TIMEOUT = (5 * 30000 * 60) + 2100;
  public static final long DEFAULT_INDEX_REBUILD_RPC_TIMEOUT = 30000 * 60; // 30 mins
  public static final long DEFAULT_INDEX_REBUILD_CLIENT_SCANNER_TIMEOUT = 30000 * 60; // 30 mins
  public static final int DEFAULT_INDEX_REBUILD_RPC_RETRIES_COUNTER = 5; // 5 total tries at rpc
                                                                         // level
  public static final int DEFAULT_INDEX_REBUILD_DISABLE_TIMESTAMP_THRESHOLD = 60000 * 60 * 24; // 24
                                                                                               // hrs
  public static final long DEFAULT_INDEX_PENDING_DISABLE_THRESHOLD = 30000; // 30 secs

  /**
   * HConstants#HIGH_QOS is the max we will see to a standard table. We go higher to differentiate
   * and give some room for things in the middle
   */
  public static final int DEFAULT_SERVER_SIDE_PRIORITY = 500;
  public static final int DEFAULT_INDEX_PRIORITY = 1000;
  public static final int DEFAULT_METADATA_PRIORITY = 2000;
  public static final int DEFAULT_INVALIDATE_METADATA_CACHE_PRIORITY = 3000;
  public static final boolean DEFAULT_ALLOW_LOCAL_INDEX = true;
  public static final int DEFAULT_INDEX_HANDLER_COUNT = 30;
  public static final int DEFAULT_METADATA_HANDLER_COUNT = 30;
  public static final int DEFAULT_SERVERSIDE_HANDLER_COUNT = 30;
  public static final int DEFAULT_INVALIDATE_CACHE_HANDLER_COUNT = 10;
  public static final int DEFAULT_SYSTEM_MAX_VERSIONS = 1;
  public static final boolean DEFAULT_SYSTEM_KEEP_DELETED_CELLS = false;

  // Retries when doing server side writes to SYSTEM.CATALOG
  // 20 retries with 100 pause = 230 seconds total retry time
  public static final int DEFAULT_METADATA_WRITE_RETRIES_NUMBER = 20;
  public static final int DEFAULT_METADATA_WRITE_RETRY_PAUSE = 100;

  public static final int DEFAULT_TRACING_PAGE_SIZE = 100;
  /**
   * Configuration key to overwrite the tablename that should be used as the target table
   */
  public static final String DEFAULT_TRACING_STATS_TABLE_NAME = "SYSTEM.TRACING_STATS";
  public static final String DEFAULT_TRACING_FREQ = Tracing.Frequency.NEVER.getKey();
  public static final double DEFAULT_TRACING_PROBABILITY_THRESHOLD = 0.05;

  public static final int DEFAULT_STATS_UPDATE_FREQ_MS = 15 * 60000; // 15min
  public static final int DEFAULT_STATS_GUIDEPOST_PER_REGION = 0; // Uses guidepost width by default
  // Since we're not taking into account the compression done by FAST_DIFF in our
  // counting of the bytes, default guidepost width to 100MB * 3 (where 3 is the
  // compression we're getting)
  public static final long DEFAULT_STATS_GUIDEPOST_WIDTH_BYTES = 3 * 100 * 1024 * 1024;
  public static final boolean DEFAULT_STATS_USE_CURRENT_TIME = true;
  public static final boolean DEFAULT_RUN_UPDATE_STATS_ASYNC = true;
  public static final boolean DEFAULT_COMMIT_STATS_ASYNC = true;
  public static final int DEFAULT_STATS_POOL_SIZE = 4;
  // Maximum size (in bytes) that cached table stats should take upm
  public static final long DEFAULT_STATS_MAX_CACHE_SIZE = 256 * 1024 * 1024;
  // Allow stats collection to be initiated by client multiple times immediately
  public static final int DEFAULT_MIN_STATS_UPDATE_FREQ_MS = 0;
  public static final int DEFAULT_STATS_CACHE_THREAD_POOL_SIZE = 4;

  public static final boolean DEFAULT_USE_REVERSE_SCAN = true;

  public static final String DEFAULT_CREATE_INDEX_STATE = PIndexState.BUILDING.toString();
  public static final boolean DEFAULT_DISABLE_ON_DROP = false;

  /**
   * Use only first time SYSTEM.SEQUENCE table is created.
   */
  public static final int DEFAULT_SEQUENCE_TABLE_SALT_BUCKETS = 0;
  /**
   * Default value for coprocessor priority is between SYSTEM and USER priority.
   */
  public static final int DEFAULT_COPROCESSOR_PRIORITY =
    Coprocessor.PRIORITY_SYSTEM / 2 + Coprocessor.PRIORITY_USER / 2; // Divide individually to
                                                                     // prevent any overflow
  public static final boolean DEFAULT_EXPLAIN_CHUNK_COUNT = true;
  public static final boolean DEFAULT_EXPLAIN_ROW_COUNT = true;
  public static final boolean DEFAULT_ALLOW_ONLINE_TABLE_SCHEMA_UPDATE = true;
  public static final int DEFAULT_RETRIES_FOR_SCHEMA_UPDATE_CHECK = 10;
  public static final long DEFAULT_DELAY_FOR_SCHEMA_UPDATE_CHECK = 5 * 1000; // 5 seconds.
  public static final boolean DEFAULT_STORE_NULLS = false;

  // TODO Change this to true as part of PHOENIX-1543
  // We'll also need this for transactions to work correctly
  public static final boolean DEFAULT_AUTO_COMMIT = false;
  public static final boolean DEFAULT_TABLE_ISTRANSACTIONAL = false;
  public static final String DEFAULT_TRANSACTION_PROVIDER =
    TransactionFactory.Provider.getDefault().name();
  public static final boolean DEFAULT_TRANSACTIONS_ENABLED = false;
  public static final boolean DEFAULT_IS_GLOBAL_METRICS_ENABLED = true;
  public static final boolean DEFAULT_IS_TABLE_LEVEL_METRICS_ENABLED = false;
  public static final boolean DEFAULT_IS_METRIC_PUBLISHER_ENABLED = false;
  public static final String DEFAULT_ALLOWED_LIST_FOR_TABLE_LEVEL_METRICS = null; // All the tables
                                                                                  // metrics will be
                                                                                  // allowed.
  public static final String DEFAULT_METRIC_PUBLISHER_CLASS_NAME =
    "org.apache.phoenix.monitoring.JmxMetricProvider";
  public static final String DEFAULT_CLIENT_METRICS_TAG = "FAT_CLIENT";

  public static final boolean DEFAULT_TRANSACTIONAL = false;
  public static final boolean DEFAULT_MULTI_TENANT = false;
  public static final boolean DEFAULT_AUTO_FLUSH = false;

  private static final String DEFAULT_CLIENT_RPC_CONTROLLER_FACTORY =
    ClientRpcControllerFactory.class.getName();

  public static final String DEFAULT_CONSISTENCY_LEVEL = Consistency.STRONG.toString();

  public static final boolean DEFAULT_USE_BYTE_BASED_REGEX = false;
  public static final boolean DEFAULT_FORCE_ROW_KEY_ORDER = false;
  public static final boolean DEFAULT_ALLOW_USER_DEFINED_FUNCTIONS = false;
  public static final boolean DEFAULT_REQUEST_LEVEL_METRICS_ENABLED = false;
  public static final boolean DEFAULT_ALLOW_VIEWS_ADD_NEW_CF_BASE_TABLE = true;
  public static final int DEFAULT_MAX_VERSIONS_TRANSACTIONAL = Integer.MAX_VALUE;

  public static final boolean DEFAULT_RETURN_SEQUENCE_VALUES = false;
  public static final String DEFAULT_EXTRA_JDBC_ARGUMENTS = "";

  public static final long DEFAULT_INDEX_POPULATION_SLEEP_TIME = 5000;

  // Phoenix Connection Query Service configuration Defaults
  public static final String DEFAULT_QUERY_SERVICES_NAME = "DEFAULT_CQSN";
  public static final String DEFAULT_CONNECTION_QUERY_SERVICE_HISTOGRAM_SIZE_RANGES =
    "1, 10, 100, 500, 1000";
  public static final boolean DEFAULT_IS_CONNECTION_QUERY_SERVICE_METRICS_PUBLISHER_ENABLED = false;
  public static final boolean DEFAULT_IS_CONNECTION_QUERY_SERVICE_METRICS_ENABLED = false;

  public static final boolean DEFAULT_RENEW_LEASE_ENABLED = true;
  public static final int DEFAULT_RUN_RENEW_LEASE_FREQUENCY_INTERVAL_MILLISECONDS =
    DEFAULT_HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD / 2;
  public static final int DEFAULT_RENEW_LEASE_THRESHOLD_MILLISECONDS =
    (3 * DEFAULT_HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD) / 4;
  public static final int DEFAULT_RENEW_LEASE_THREAD_POOL_SIZE = 10;
  public static final boolean DEFAULT_LOCAL_INDEX_CLIENT_UPGRADE = true;
  public static final float DEFAULT_LIMITED_QUERY_SERIAL_THRESHOLD = 0.2f;

  public static final boolean DEFAULT_INDEX_ASYNC_BUILD_ENABLED = true;

  public static final String DEFAULT_CLIENT_CACHE_ENCODING =
    PTableRefFactory.Encoding.OBJECT.toString();
  public static final boolean DEFAULT_AUTO_UPGRADE_ENABLED = true;
  public static final int DEFAULT_CLIENT_CONNECTION_CACHE_MAX_DURATION = 86400000;
  public static final int DEFAULT_COLUMN_ENCODED_BYTES =
    QualifierEncodingScheme.TWO_BYTE_QUALIFIERS.getSerializedMetadataValue();
  public static final String DEFAULT_IMMUTABLE_STORAGE_SCHEME =
    ImmutableStorageScheme.SINGLE_CELL_ARRAY_WITH_OFFSETS.toString();
  public static final String DEFAULT_MULTITENANT_IMMUTABLE_STORAGE_SCHEME =
    ImmutableStorageScheme.ONE_CELL_PER_COLUMN.toString();

  // by default, max connections from one client to one cluster is unlimited
  public static final int DEFAULT_CLIENT_CONNECTION_MAX_ALLOWED_CONNECTIONS = 0;
  // by default, max internal connections from one client to one cluster is unlimited
  public static final int DEFAULT_INTERNAL_CONNECTION_MAX_ALLOWED_CONNECTIONS = 0;

  public static final boolean DEFAULT_CONNECTION_ACTIVITY_LOGGING_ENABLED = false;
  public static final boolean DEFAULT_CONNECTION_EXPLAIN_PLAN_LOGGING_ENABLED = false;
  public static final int DEFAULT_CONNECTION_ACTIVITY_LOGGING_INTERVAL_IN_MINS = 15;
  public static final boolean DEFAULT_STATS_COLLECTION_ENABLED = true;
  public static final boolean DEFAULT_USE_STATS_FOR_PARALLELIZATION = true;

  // Security defaults
  public static final boolean DEFAULT_PHOENIX_ACLS_ENABLED = false;

  public static final int DEFAULT_SMALL_SCAN_THRESHOLD = 100;

  /**
   * Metadata caching configs, see https://issues.apache.org/jira/browse/PHOENIX-6883. Disable the
   * boolean flags and set UCF=always to disable the caching re-design. Disable caching re-design if
   * you use Online Data Format Change since the cutover logic is currently incompatible and clients
   * may not learn about the physical table change. See
   * https://issues.apache.org/jira/browse/PHOENIX-7284. Disable caching re-design if your clients
   * will not have ADMIN perms to call region server RPC. See
   * https://issues.apache.org/jira/browse/HBASE-28508
   */
  public static final long DEFAULT_UPDATE_CACHE_FREQUENCY =
    (long) ConnectionProperty.UPDATE_CACHE_FREQUENCY.getValue("ALWAYS");
  public static final boolean DEFAULT_LAST_DDL_TIMESTAMP_VALIDATION_ENABLED = false;
  public static final boolean DEFAULT_PHOENIX_METADATA_INVALIDATE_CACHE_ENABLED = false;
  public static final int DEFAULT_PHOENIX_METADATA_CACHE_INVALIDATION_THREAD_POOL_SIZE = 20;

  // default system task handling interval in milliseconds
  public static final long DEFAULT_TASK_HANDLING_INTERVAL_MS = 60 * 1000; // 1 min
  public static final long DEFAULT_TASK_HANDLING_MAX_INTERVAL_MS = 30 * 60 * 1000; // 30 min
  public static final long DEFAULT_TASK_HANDLING_INITIAL_DELAY_MS = 10 * 1000; // 10 sec

  public static final long DEFAULT_GLOBAL_INDEX_ROW_AGE_THRESHOLD_TO_DELETE_MS =
    7 * 24 * 60 * 60 * 1000; /* 7 days */
  public static final boolean DEFAULT_INDEX_REGION_OBSERVER_ENABLED = true;

  public static final String DEFAULT_INDEX_REGION_OBSERVER_ENABLED_ALL_TABLES =
    Boolean.toString(true);
  public static final boolean DEFAULT_PHOENIX_SERVER_PAGING_ENABLED = true;
  public static final long DEFAULT_INDEX_REBUILD_PAGE_SIZE_IN_ROWS = 32 * 1024;
  public static final long DEFAULT_INDEX_PAGE_SIZE_IN_ROWS = 32 * 1024;

  public static final boolean DEFAULT_ALLOW_SPLITTABLE_SYSTEM_CATALOG_ROLLBACK = false;

  public static final boolean DEFAULT_PROPERTY_POLICY_PROVIDER_ENABLED = true;

  public static final String DEFAULT_SCHEMA = null;
  public static final String DEFAULT_UPLOAD_BINARY_DATA_TYPE_ENCODING = "BASE64"; // for backward
                                                                                  // compatibility,
                                                                                  // till
                                                                                  // 4.10, psql and
                                                                                  // CSVBulkLoad
                                                                                  // expects binary
                                                                                  // data to be base
                                                                                  // 64
                                                                                  // encoded
  // RS -> RS calls for upsert select statements are disabled by default
  public static final boolean DEFAULT_ENABLE_SERVER_UPSERT_SELECT = false;

  // By default generally allow server trigger mutations
  public static final boolean DEFAULT_ENABLE_SERVER_SIDE_DELETE_MUTATIONS = true;
  public static final boolean DEFAULT_ENABLE_SERVER_SIDE_UPSERT_MUTATIONS = true;

  public static final boolean DEFAULT_COST_BASED_OPTIMIZER_ENABLED = false;
  public static final boolean DEFAULT_WILDCARD_QUERY_DYNAMIC_COLS_ATTRIB = false;
  public static final String DEFAULT_LOGGING_LEVEL = LogLevel.OFF.name();
  public static final String DEFAULT_AUDIT_LOGGING_LEVEL = LogLevel.OFF.name();
  public static final String DEFAULT_LOG_SAMPLE_RATE = "1.0";
  public static final int DEFAULT_LOG_SALT_BUCKETS = 32;
  public static final int DEFAULT_SALT_BUCKETS = 0;

  public static final boolean DEFAULT_SYSTEM_CATALOG_SPLITTABLE = true;

  public static final String DEFAULT_GUIDE_POSTS_CACHE_FACTORY_CLASS =
    "org.apache.phoenix.query.DefaultGuidePostsCacheFactory";

  public static final boolean DEFAULT_LONG_VIEW_INDEX_ENABLED = false;

  public static final boolean DEFAULT_PENDING_MUTATIONS_DDL_THROW = false;
  public static final boolean DEFAULT_SKIP_SYSTEM_TABLES_EXISTENCE_CHECK = false;
  public static final boolean DEFAULT_MOVE_CHILD_LINKS_DURING_UPGRADE_ENABLED = true;
  public static final int DEFAULT_TIMEOUT_DURING_UPGRADE_MS = 60000 * 30; // 30 mins
  public static final int DEFAULT_SCAN_PAGE_SIZE = 32768;
  public static final boolean DEFAULT_APPLY_TIME_ZONE_DISPLACMENT = false;
  public static final boolean DEFAULT_PHOENIX_TABLE_TTL_ENABLED = true;
  public static final boolean DEFAULT_PHOENIX_COMPACTION_ENABLED = true;
  public static final boolean DEFAULT_PHOENIX_VIEW_TTL_ENABLED = true;
  public static final int DEFAULT_PHOENIX_VIEW_TTL_TENANT_VIEWS_PER_SCAN_LIMIT = 100;

  public static final int DEFAULT_MAX_REGION_LOCATIONS_SIZE_EXPLAIN_PLAN = 5;
  public static final boolean DEFAULT_SERVER_MERGE_FOR_UNCOVERED_INDEX = true;

  public static final boolean DEFAULT_PHOENIX_GET_METADATA_READ_LOCK_ENABLED = true;

  public static final int DEFAULT_PHOENIX_STREAMS_GET_TABLE_REGIONS_TIMEOUT = 300000; // 5 minutes
  public static final boolean DEFAULT_SYSTEM_CATALOG_INDEXES_ENABLED = false;

  public static final Boolean DEFAULT_CLUSTER_ROLE_BASED_MUTATION_BLOCK_ENABLED = false;
  public static final Boolean DEFAULT_CQSI_THREAD_POOL_ENABLED = false;
  public static final int DEFAULT_CQSI_THREAD_POOL_KEEP_ALIVE_SECONDS = 60;
  public static final int DEFAULT_CQSI_THREAD_POOL_CORE_POOL_SIZE = 25;
  public static final int DEFAULT_CQSI_THREAD_POOL_MAX_THREADS = 25;
  public static final int DEFAULT_CQSI_THREAD_POOL_MAX_QUEUE = 512;
  public static final Boolean DEFAULT_CQSI_THREAD_POOL_ALLOW_CORE_THREAD_TIMEOUT = true;
  public static final Boolean DEFAULT_CQSI_THREAD_POOL_METRICS_ENABLED = false;
  public static final int DEFAULT_CDC_TTL_MUTATION_MAX_RETRIES = 5;
  public static final int DEFAULT_CDC_TTL_MUTATION_BATCH_SIZE = 50;
  public static final int DEFAULT_CDC_TTL_SHARED_CACHE_EXPIRY_SECONDS = 1200;

  public static final long DEFAULT_PHOENIX_CDC_STREAM_PARTITION_EXPIRY_MIN_AGE_MS =
    30 * 60 * 60 * 1000; // 30 hours

  private final Configuration config;

  private QueryServicesOptions(Configuration config) {
    this.config = config;
  }

  public ReadOnlyProps getProps(ReadOnlyProps defaultProps) {
    return new ReadOnlyProps(defaultProps, config.iterator());
  }

  public QueryServicesOptions setAll(ReadOnlyProps props) {
    for (Entry<String, String> entry : props) {
      config.set(entry.getKey(), entry.getValue());
    }
    return this;
  }

  public static QueryServicesOptions withDefaults() {
    Configuration config = HBaseFactoryProvider.getConfigurationFactory().getConfiguration();
    QueryServicesOptions options = new QueryServicesOptions(config)
      .setIfUnset(STATS_USE_CURRENT_TIME_ATTRIB, DEFAULT_STATS_USE_CURRENT_TIME)
      .setIfUnset(RUN_UPDATE_STATS_ASYNC, DEFAULT_RUN_UPDATE_STATS_ASYNC)
      .setIfUnset(COMMIT_STATS_ASYNC, DEFAULT_COMMIT_STATS_ASYNC)
      .setIfUnset(KEEP_ALIVE_MS_ATTRIB, DEFAULT_KEEP_ALIVE_MS)
      .setIfUnset(THREAD_POOL_SIZE_ATTRIB, DEFAULT_THREAD_POOL_SIZE)
      .setIfUnset(QUEUE_SIZE_ATTRIB, DEFAULT_QUEUE_SIZE)
      .setIfUnset(THREAD_TIMEOUT_MS_ATTRIB, DEFAULT_THREAD_TIMEOUT_MS)
      .setIfUnset(CLIENT_SPOOL_THRESHOLD_BYTES_ATTRIB, DEFAULT_CLIENT_SPOOL_THRESHOLD_BYTES)
      .setIfUnset(SERVER_SPOOL_THRESHOLD_BYTES_ATTRIB, DEFAULT_SERVER_SPOOL_THRESHOLD_BYTES)
      .setIfUnset(SPOOL_DIRECTORY, DEFAULT_SPOOL_DIRECTORY)
      .setIfUnset(MAX_MEMORY_PERC_ATTRIB, DEFAULT_MAX_MEMORY_PERC)
      .setIfUnset(MAX_TENANT_MEMORY_PERC_ATTRIB, DEFAULT_MAX_TENANT_MEMORY_PERC)
      .setIfUnset(MAX_SERVER_CACHE_SIZE_ATTRIB, DEFAULT_MAX_SERVER_CACHE_SIZE)
      .setIfUnset(SCAN_CACHE_SIZE_ATTRIB, DEFAULT_SCAN_CACHE_SIZE)
      .setIfUnset(DATE_FORMAT_ATTRIB, DEFAULT_DATE_FORMAT)
      .setIfUnset(DATE_FORMAT_TIMEZONE_ATTRIB, DEFAULT_DATE_FORMAT_TIMEZONE)
      .setIfUnset(STATS_UPDATE_FREQ_MS_ATTRIB, DEFAULT_STATS_UPDATE_FREQ_MS)
      .setIfUnset(MIN_STATS_UPDATE_FREQ_MS_ATTRIB, DEFAULT_MIN_STATS_UPDATE_FREQ_MS)
      .setIfUnset(STATS_CACHE_THREAD_POOL_SIZE, DEFAULT_STATS_CACHE_THREAD_POOL_SIZE)
      .setIfUnset(CALL_QUEUE_ROUND_ROBIN_ATTRIB, DEFAULT_CALL_QUEUE_ROUND_ROBIN)
      .setIfUnset(MAX_MUTATION_SIZE_ATTRIB, DEFAULT_MAX_MUTATION_SIZE)
      .setIfUnset(ROW_KEY_ORDER_SALTED_TABLE_ATTRIB, DEFAULT_FORCE_ROW_KEY_ORDER)
      .setIfUnset(USE_INDEXES_ATTRIB, DEFAULT_USE_INDEXES)
      .setIfUnset(IMMUTABLE_ROWS_ATTRIB, DEFAULT_IMMUTABLE_ROWS)
      .setIfUnset(INDEX_MUTATE_BATCH_SIZE_THRESHOLD_ATTRIB,
        DEFAULT_INDEX_MUTATE_BATCH_SIZE_THRESHOLD)
      .setIfUnset(MAX_SPOOL_TO_DISK_BYTES_ATTRIB, DEFAULT_MAX_SPOOL_TO_DISK_BYTES)
      .setIfUnset(DROP_METADATA_ATTRIB, DEFAULT_DROP_METADATA)
      .setIfUnset(GROUPBY_SPILLABLE_ATTRIB, DEFAULT_GROUPBY_SPILLABLE)
      .setIfUnset(GROUPBY_MAX_CACHE_SIZE_ATTRIB, DEFAULT_GROUPBY_MAX_CACHE_MAX)
      .setIfUnset(GROUPBY_SPILL_FILES_ATTRIB, DEFAULT_GROUPBY_SPILL_FILES)
      .setIfUnset(SEQUENCE_CACHE_SIZE_ATTRIB, DEFAULT_SEQUENCE_CACHE_SIZE)
      .setIfUnset(SCAN_RESULT_CHUNK_SIZE, DEFAULT_SCAN_RESULT_CHUNK_SIZE)
      .setIfUnset(ALLOW_ONLINE_TABLE_SCHEMA_UPDATE, DEFAULT_ALLOW_ONLINE_TABLE_SCHEMA_UPDATE)
      .setIfUnset(NUM_RETRIES_FOR_SCHEMA_UPDATE_CHECK, DEFAULT_RETRIES_FOR_SCHEMA_UPDATE_CHECK)
      .setIfUnset(DELAY_FOR_SCHEMA_UPDATE_CHECK, DEFAULT_DELAY_FOR_SCHEMA_UPDATE_CHECK)
      .setIfUnset(GLOBAL_METRICS_ENABLED, DEFAULT_IS_GLOBAL_METRICS_ENABLED)
      .setIfUnset(RpcControllerFactory.CUSTOM_CONTROLLER_CONF_KEY,
        DEFAULT_CLIENT_RPC_CONTROLLER_FACTORY)
      .setIfUnset(USE_BYTE_BASED_REGEX_ATTRIB, DEFAULT_USE_BYTE_BASED_REGEX)
      .setIfUnset(FORCE_ROW_KEY_ORDER_ATTRIB, DEFAULT_FORCE_ROW_KEY_ORDER)
      .setIfUnset(COLLECT_REQUEST_LEVEL_METRICS, DEFAULT_REQUEST_LEVEL_METRICS_ENABLED)
      .setIfUnset(ALLOW_VIEWS_ADD_NEW_CF_BASE_TABLE, DEFAULT_ALLOW_VIEWS_ADD_NEW_CF_BASE_TABLE)
      .setIfUnset(ALLOW_VIEWS_ADD_NEW_CF_BASE_TABLE, DEFAULT_ALLOW_VIEWS_ADD_NEW_CF_BASE_TABLE)
      .setIfUnset(RENEW_LEASE_THRESHOLD_MILLISECONDS, DEFAULT_RENEW_LEASE_THRESHOLD_MILLISECONDS)
      .setIfUnset(RUN_RENEW_LEASE_FREQUENCY_INTERVAL_MILLISECONDS,
        DEFAULT_RUN_RENEW_LEASE_FREQUENCY_INTERVAL_MILLISECONDS)
      .setIfUnset(RENEW_LEASE_THREAD_POOL_SIZE, DEFAULT_RENEW_LEASE_THREAD_POOL_SIZE)
      .setIfUnset(IS_NAMESPACE_MAPPING_ENABLED, DEFAULT_IS_NAMESPACE_MAPPING_ENABLED)
      .setIfUnset(IS_SYSTEM_TABLE_MAPPED_TO_NAMESPACE, DEFAULT_IS_SYSTEM_TABLE_MAPPED_TO_NAMESPACE)
      .setIfUnset(LOCAL_INDEX_CLIENT_UPGRADE_ATTRIB, DEFAULT_LOCAL_INDEX_CLIENT_UPGRADE)
      .setIfUnset(AUTO_UPGRADE_ENABLED, DEFAULT_AUTO_UPGRADE_ENABLED)
      .setIfUnset(UPLOAD_BINARY_DATA_TYPE_ENCODING, DEFAULT_UPLOAD_BINARY_DATA_TYPE_ENCODING)
      .setIfUnset(TRACING_ENABLED, DEFAULT_TRACING_ENABLED)
      .setIfUnset(TRACING_BATCH_SIZE, DEFAULT_TRACING_BATCH_SIZE)
      .setIfUnset(TRACING_THREAD_POOL_SIZE, DEFAULT_TRACING_THREAD_POOL_SIZE)
      .setIfUnset(STATS_COLLECTION_ENABLED, DEFAULT_STATS_COLLECTION_ENABLED)
      .setIfUnset(USE_STATS_FOR_PARALLELIZATION, DEFAULT_USE_STATS_FOR_PARALLELIZATION)
      .setIfUnset(USE_STATS_FOR_PARALLELIZATION, DEFAULT_USE_STATS_FOR_PARALLELIZATION)
      .setIfUnset(UPLOAD_BINARY_DATA_TYPE_ENCODING, DEFAULT_UPLOAD_BINARY_DATA_TYPE_ENCODING)
      .setIfUnset(COST_BASED_OPTIMIZER_ENABLED, DEFAULT_COST_BASED_OPTIMIZER_ENABLED)
      .setIfUnset(PHOENIX_ACLS_ENABLED, DEFAULT_PHOENIX_ACLS_ENABLED)
      .setIfUnset(LOG_LEVEL, DEFAULT_LOGGING_LEVEL)
      .setIfUnset(LOG_SAMPLE_RATE, DEFAULT_LOG_SAMPLE_RATE)
      .setIfUnset("data.tx.pre.014.changeset.key", Boolean.FALSE.toString())
      .setIfUnset(CLIENT_METRICS_TAG, DEFAULT_CLIENT_METRICS_TAG)
      .setIfUnset(CLIENT_INDEX_ASYNC_THRESHOLD, DEFAULT_CLIENT_INDEX_ASYNC_THRESHOLD)
      .setIfUnset(QUERY_SERVICES_NAME, DEFAULT_QUERY_SERVICES_NAME)
      .setIfUnset(INDEX_CREATE_DEFAULT_STATE, DEFAULT_CREATE_INDEX_STATE)
      .setIfUnset(CONNECTION_QUERY_SERVICE_HISTOGRAM_SIZE_RANGES,
        DEFAULT_CONNECTION_QUERY_SERVICE_HISTOGRAM_SIZE_RANGES)
      .setIfUnset(CONNECTION_QUERY_SERVICE_METRICS_ENABLED,
        DEFAULT_IS_CONNECTION_QUERY_SERVICE_METRICS_ENABLED)
      .setIfUnset(CONNECTION_QUERY_SERVICE_METRICS_PUBLISHER_ENABLED,
        DEFAULT_IS_CONNECTION_QUERY_SERVICE_METRICS_PUBLISHER_ENABLED)
      .setIfUnset(SKIP_SYSTEM_TABLES_EXISTENCE_CHECK, DEFAULT_SKIP_SYSTEM_TABLES_EXISTENCE_CHECK)
      .setIfUnset(MAX_IN_LIST_SKIP_SCAN_SIZE, DEFAULT_MAX_IN_LIST_SKIP_SCAN_SIZE)
      .setIfUnset(MAX_REGION_LOCATIONS_SIZE_EXPLAIN_PLAN,
        DEFAULT_MAX_REGION_LOCATIONS_SIZE_EXPLAIN_PLAN)
      .setIfUnset(SERVER_MERGE_FOR_UNCOVERED_INDEX, DEFAULT_SERVER_MERGE_FOR_UNCOVERED_INDEX)
      .setIfUnset(MAX_IN_LIST_SKIP_SCAN_SIZE, DEFAULT_MAX_IN_LIST_SKIP_SCAN_SIZE)
      .setIfUnset(CONNECTION_ACTIVITY_LOGGING_ENABLED, DEFAULT_CONNECTION_ACTIVITY_LOGGING_ENABLED)
      .setIfUnset(CONNECTION_EXPLAIN_PLAN_LOGGING_ENABLED,
        DEFAULT_CONNECTION_EXPLAIN_PLAN_LOGGING_ENABLED)
      .setIfUnset(CONNECTION_ACTIVITY_LOGGING_INTERVAL,
        DEFAULT_CONNECTION_ACTIVITY_LOGGING_INTERVAL_IN_MINS)
      .setIfUnset(CLUSTER_ROLE_BASED_MUTATION_BLOCK_ENABLED,
        DEFAULT_CLUSTER_ROLE_BASED_MUTATION_BLOCK_ENABLED)
      .setIfUnset(CQSI_THREAD_POOL_ENABLED, DEFAULT_CQSI_THREAD_POOL_ENABLED)
      .setIfUnset(CQSI_THREAD_POOL_KEEP_ALIVE_SECONDS, DEFAULT_CQSI_THREAD_POOL_KEEP_ALIVE_SECONDS)
      .setIfUnset(CQSI_THREAD_POOL_CORE_POOL_SIZE, DEFAULT_CQSI_THREAD_POOL_CORE_POOL_SIZE)
      .setIfUnset(CQSI_THREAD_POOL_MAX_THREADS, DEFAULT_CQSI_THREAD_POOL_MAX_THREADS)
      .setIfUnset(CQSI_THREAD_POOL_MAX_QUEUE, DEFAULT_CQSI_THREAD_POOL_MAX_QUEUE)
      .setIfUnset(CQSI_THREAD_POOL_ALLOW_CORE_THREAD_TIMEOUT,
        DEFAULT_CQSI_THREAD_POOL_ALLOW_CORE_THREAD_TIMEOUT)
      .setIfUnset(CQSI_THREAD_POOL_METRICS_ENABLED, DEFAULT_CQSI_THREAD_POOL_METRICS_ENABLED)
      .setIfUnset(CDC_TTL_MUTATION_MAX_RETRIES, DEFAULT_CDC_TTL_MUTATION_MAX_RETRIES)
      .setIfUnset(CDC_TTL_MUTATION_BATCH_SIZE, DEFAULT_CDC_TTL_MUTATION_BATCH_SIZE)
      .setIfUnset(CDC_TTL_SHARED_CACHE_EXPIRY_SECONDS, DEFAULT_CDC_TTL_SHARED_CACHE_EXPIRY_SECONDS);

    // HBase sets this to 1, so we reset it to something more appropriate.
    // Hopefully HBase will change this, because we can't know if a user set
    // it to 1, so we'll change it.
    int scanCaching = config.getInt(SCAN_CACHE_SIZE_ATTRIB, 0);
    if (scanCaching == 1) {
      config.setInt(SCAN_CACHE_SIZE_ATTRIB, DEFAULT_SCAN_CACHE_SIZE);
    } else if (scanCaching <= 0) { // Provides the user with a way of setting it to 1
      config.setInt(SCAN_CACHE_SIZE_ATTRIB, 1);
    }
    return options;
  }

  public Configuration getConfiguration() {
    return config;
  }

  private QueryServicesOptions setIfUnset(String name, int value) {
    config.setIfUnset(name, Integer.toString(value));
    return this;
  }

  private QueryServicesOptions setIfUnset(String name, boolean value) {
    config.setIfUnset(name, Boolean.toString(value));
    return this;
  }

  private QueryServicesOptions setIfUnset(String name, long value) {
    config.setIfUnset(name, Long.toString(value));
    return this;
  }

  private QueryServicesOptions setIfUnset(String name, String value) {
    config.setIfUnset(name, value);
    return this;
  }

  public QueryServicesOptions setKeepAliveMs(int keepAliveMs) {
    return set(KEEP_ALIVE_MS_ATTRIB, keepAliveMs);
  }

  public QueryServicesOptions setThreadPoolSize(int threadPoolSize) {
    return set(THREAD_POOL_SIZE_ATTRIB, threadPoolSize);
  }

  public QueryServicesOptions setQueueSize(int queueSize) {
    config.setInt(QUEUE_SIZE_ATTRIB, queueSize);
    return this;
  }

  public QueryServicesOptions setThreadTimeoutMs(int threadTimeoutMs) {
    return set(THREAD_TIMEOUT_MS_ATTRIB, threadTimeoutMs);
  }

  public QueryServicesOptions setClientSpoolThresholdBytes(long spoolThresholdBytes) {
    return set(CLIENT_SPOOL_THRESHOLD_BYTES_ATTRIB, spoolThresholdBytes);
  }

  public QueryServicesOptions setServerSpoolThresholdBytes(long spoolThresholdBytes) {
    return set(SERVER_SPOOL_THRESHOLD_BYTES_ATTRIB, spoolThresholdBytes);
  }

  public QueryServicesOptions setSpoolDirectory(String spoolDirectory) {
    return set(SPOOL_DIRECTORY, spoolDirectory);
  }

  public QueryServicesOptions setMaxMemoryPerc(int maxMemoryPerc) {
    return set(MAX_MEMORY_PERC_ATTRIB, maxMemoryPerc);
  }

  public QueryServicesOptions setMaxTenantMemoryPerc(int maxTenantMemoryPerc) {
    return set(MAX_TENANT_MEMORY_PERC_ATTRIB, maxTenantMemoryPerc);
  }

  public QueryServicesOptions setMaxServerCacheSize(long maxServerCacheSize) {
    return set(MAX_SERVER_CACHE_SIZE_ATTRIB, maxServerCacheSize);
  }

  public QueryServicesOptions setMaxServerMetaDataCacheSize(long maxMetaDataCacheSize) {
    return set(MAX_SERVER_METADATA_CACHE_SIZE_ATTRIB, maxMetaDataCacheSize);
  }

  public QueryServicesOptions setMaxClientMetaDataCacheSize(long maxMetaDataCacheSize) {
    return set(MAX_CLIENT_METADATA_CACHE_SIZE_ATTRIB, maxMetaDataCacheSize);
  }

  public QueryServicesOptions setScanFetchSize(int scanFetchSize) {
    return set(SCAN_CACHE_SIZE_ATTRIB, scanFetchSize);
  }

  public QueryServicesOptions setDateFormat(String dateFormat) {
    return set(DATE_FORMAT_ATTRIB, dateFormat);
  }

  public QueryServicesOptions setCallQueueRoundRobin(boolean isRoundRobin) {
    return set(CALL_QUEUE_PRODUCER_ATTRIB_NAME, isRoundRobin);
  }

  public QueryServicesOptions setMaxMutateSize(int maxMutateSize) {
    return set(MAX_MUTATION_SIZE_ATTRIB, maxMutateSize);
  }

  @Deprecated
  public QueryServicesOptions setMutateBatchSize(int mutateBatchSize) {
    return set(MUTATE_BATCH_SIZE_ATTRIB, mutateBatchSize);
  }

  public QueryServicesOptions setDropMetaData(boolean dropMetadata) {
    return set(DROP_METADATA_ATTRIB, dropMetadata);
  }

  public QueryServicesOptions setGroupBySpill(boolean enabled) {
    return set(GROUPBY_SPILLABLE_ATTRIB, enabled);
  }

  public QueryServicesOptions setGroupBySpillMaxCacheSize(long size) {
    return set(GROUPBY_MAX_CACHE_SIZE_ATTRIB, size);
  }

  public QueryServicesOptions setGroupBySpillNumSpillFiles(long num) {
    return set(GROUPBY_SPILL_FILES_ATTRIB, num);
  }

  QueryServicesOptions set(String name, boolean value) {
    config.set(name, Boolean.toString(value));
    return this;
  }

  QueryServicesOptions set(String name, int value) {
    config.set(name, Integer.toString(value));
    return this;
  }

  QueryServicesOptions set(String name, String value) {
    config.set(name, value);
    return this;
  }

  QueryServicesOptions set(String name, long value) {
    config.set(name, Long.toString(value));
    return this;
  }

  public int getKeepAliveMs() {
    return config.getInt(KEEP_ALIVE_MS_ATTRIB, DEFAULT_KEEP_ALIVE_MS);
  }

  public int getThreadPoolSize() {
    return config.getInt(THREAD_POOL_SIZE_ATTRIB, DEFAULT_THREAD_POOL_SIZE);
  }

  public int getQueueSize() {
    return config.getInt(QUEUE_SIZE_ATTRIB, DEFAULT_QUEUE_SIZE);
  }

  public int getMaxMemoryPerc() {
    return config.getInt(MAX_MEMORY_PERC_ATTRIB, DEFAULT_MAX_MEMORY_PERC);
  }

  public int getMaxMutateSize() {
    return config.getInt(MAX_MUTATION_SIZE_ATTRIB, DEFAULT_MAX_MUTATION_SIZE);
  }

  @Deprecated
  public int getMutateBatchSize() {
    return config.getInt(MUTATE_BATCH_SIZE_ATTRIB, DEFAULT_MUTATE_BATCH_SIZE);
  }

  public String getClientMetricTag() {
    return config.get(QueryServices.CLIENT_METRICS_TAG, DEFAULT_CLIENT_METRICS_TAG);
  }

  public boolean isUseIndexes() {
    return config.getBoolean(USE_INDEXES_ATTRIB, DEFAULT_USE_INDEXES);
  }

  public boolean isImmutableRows() {
    return config.getBoolean(IMMUTABLE_ROWS_ATTRIB, DEFAULT_IMMUTABLE_ROWS);
  }

  public boolean isDropMetaData() {
    return config.getBoolean(DROP_METADATA_ATTRIB, DEFAULT_DROP_METADATA);
  }

  public boolean isSpillableGroupByEnabled() {
    return config.getBoolean(GROUPBY_SPILLABLE_ATTRIB, DEFAULT_GROUPBY_SPILLABLE);
  }

  public long getSpillableGroupByMaxCacheSize() {
    return config.getLongBytes(GROUPBY_MAX_CACHE_SIZE_ATTRIB, DEFAULT_GROUPBY_MAX_CACHE_MAX);
  }

  public int getSpillableGroupByNumSpillFiles() {
    return config.getInt(GROUPBY_SPILL_FILES_ATTRIB, DEFAULT_GROUPBY_SPILL_FILES);
  }

  public boolean isTracingEnabled() {
    return config.getBoolean(TRACING_ENABLED, DEFAULT_TRACING_ENABLED);
  }

  public QueryServicesOptions setTracingEnabled(boolean enable) {
    config.setBoolean(TRACING_ENABLED, enable);
    return this;
  }

  public int getTracingThreadPoolSize() {
    return config.getInt(TRACING_THREAD_POOL_SIZE, DEFAULT_TRACING_THREAD_POOL_SIZE);
  }

  public int getTracingBatchSize() {
    return config.getInt(TRACING_BATCH_SIZE, DEFAULT_TRACING_BATCH_SIZE);
  }

  public int getTracingTraceBufferSize() {
    return config.getInt(TRACING_TRACE_BUFFER_SIZE, DEFAULT_TRACING_TRACE_BUFFER_SIZE);
  }

  public String getTableName() {
    return config.get(TRACING_STATS_TABLE_NAME_ATTRIB, DEFAULT_TRACING_STATS_TABLE_NAME);
  }

  public boolean isGlobalMetricsEnabled() {
    return config.getBoolean(GLOBAL_METRICS_ENABLED, DEFAULT_IS_GLOBAL_METRICS_ENABLED);
  }

  public String getMetricPublisherClass() {
    return config.get(METRIC_PUBLISHER_CLASS_NAME, DEFAULT_METRIC_PUBLISHER_CLASS_NAME);
  }

  public String getAllowedListTableNames() {
    return config.get(ALLOWED_LIST_FOR_TABLE_LEVEL_METRICS,
      DEFAULT_ALLOWED_LIST_FOR_TABLE_LEVEL_METRICS);
  }

  public boolean isTableLevelMetricsEnabled() {
    return config.getBoolean(TABLE_LEVEL_METRICS_ENABLED, DEFAULT_IS_TABLE_LEVEL_METRICS_ENABLED);
  }

  public boolean isCQSIThreadPoolMetricsEnabled() {
    return config.getBoolean(CQSI_THREAD_POOL_METRICS_ENABLED,
      DEFAULT_CQSI_THREAD_POOL_METRICS_ENABLED);
  }

  public void setCQSIThreadPoolMetricsEnabled(boolean enabled) {
    config.setBoolean(CQSI_THREAD_POOL_METRICS_ENABLED, enabled);
  }

  public void setTableLevelMetricsEnabled() {
    set(TABLE_LEVEL_METRICS_ENABLED, true);
  }

  public boolean isMetricPublisherEnabled() {
    return config.getBoolean(METRIC_PUBLISHER_ENABLED, DEFAULT_IS_METRIC_PUBLISHER_ENABLED);
  }

  public boolean isConnectionQueryServiceMetricsEnabled() {
    return config.getBoolean(CONNECTION_QUERY_SERVICE_METRICS_ENABLED,
      DEFAULT_IS_CONNECTION_QUERY_SERVICE_METRICS_ENABLED);
  }

  public boolean isConnectionQueryServiceMetricsPublisherEnabled() {
    return config.getBoolean(CONNECTION_QUERY_SERVICE_METRICS_PUBLISHER_ENABLED,
      DEFAULT_IS_CONNECTION_QUERY_SERVICE_METRICS_PUBLISHER_ENABLED);
  }

  public String getQueryServicesName() {
    return config.get(QUERY_SERVICES_NAME, DEFAULT_QUERY_SERVICES_NAME);
  }

  public void setConnectionQueryServiceMetricsEnabled() {
    set(CONNECTION_QUERY_SERVICE_METRICS_ENABLED, true);
  }

  public String getConnectionQueryServiceMetricsPublisherClass() {
    return config.get(CONNECTION_QUERY_SERVICE_METRICS_PUBLISHER_CLASSNAME,
      DEFAULT_METRIC_PUBLISHER_CLASS_NAME);
  }

  @VisibleForTesting
  public void setAllowedListForTableLevelMetrics(String tableNameList) {
    set(ALLOWED_LIST_FOR_TABLE_LEVEL_METRICS, tableNameList);
  }

  public boolean isUseByteBasedRegex() {
    return config.getBoolean(USE_BYTE_BASED_REGEX_ATTRIB, DEFAULT_USE_BYTE_BASED_REGEX);
  }

  public int getScanCacheSize() {
    return config.getInt(SCAN_CACHE_SIZE_ATTRIB, DEFAULT_SCAN_CACHE_SIZE);
  }

  public QueryServicesOptions setMaxServerCacheTTLMs(int ttl) {
    return set(MAX_SERVER_CACHE_TIME_TO_LIVE_MS_ATTRIB, ttl);
  }

  public QueryServicesOptions setMasterInfoPort(int port) {
    return set(MASTER_INFO_PORT_ATTRIB, port);
  }

  public QueryServicesOptions setRegionServerInfoPort(int port) {
    return set(REGIONSERVER_INFO_PORT_ATTRIB, port);
  }

  public QueryServicesOptions setRegionServerLeasePeriodMs(int period) {
    return set(HBASE_CLIENT_SCANNER_TIMEOUT_ATTRIB, period);
  }

  public QueryServicesOptions setRpcTimeoutMs(int timeout) {
    return set(RPC_TIMEOUT_ATTRIB, timeout);
  }

  public QueryServicesOptions setUseIndexes(boolean useIndexes) {
    return set(USE_INDEXES_ATTRIB, useIndexes);
  }

  public QueryServicesOptions setImmutableRows(boolean isImmutableRows) {
    return set(IMMUTABLE_ROWS_ATTRIB, isImmutableRows);
  }

  public QueryServicesOptions setWALEditCodec(String walEditCodec) {
    return set(WAL_EDIT_CODEC_ATTRIB, walEditCodec);
  }

  public QueryServicesOptions setStatsHistogramDepthBytes(long byteDepth) {
    return set(STATS_GUIDEPOST_WIDTH_BYTES_ATTRIB, byteDepth);
  }

  public QueryServicesOptions setStatsUpdateFrequencyMs(int frequencyMs) {
    return set(STATS_UPDATE_FREQ_MS_ATTRIB, frequencyMs);
  }

  public QueryServicesOptions setMinStatsUpdateFrequencyMs(int frequencyMs) {
    return set(MIN_STATS_UPDATE_FREQ_MS_ATTRIB, frequencyMs);
  }

  public QueryServicesOptions setStatsCacheThreadPoolSize(int threadPoolSize) {
    return set(STATS_CACHE_THREAD_POOL_SIZE, threadPoolSize);
  }

  public QueryServicesOptions setSequenceSaltBuckets(int saltBuckets) {
    config.setInt(SEQUENCE_SALT_BUCKETS_ATTRIB, saltBuckets);
    return this;
  }

  public QueryServicesOptions setExplainChunkCount(boolean showChunkCount) {
    config.setBoolean(EXPLAIN_CHUNK_COUNT_ATTRIB, showChunkCount);
    return this;
  }

  public QueryServicesOptions setTransactionsEnabled(boolean transactionsEnabled) {
    config.setBoolean(TRANSACTIONS_ENABLED, transactionsEnabled);
    return this;
  }

  public QueryServicesOptions setExplainRowCount(boolean showRowCount) {
    config.setBoolean(EXPLAIN_ROW_COUNT_ATTRIB, showRowCount);
    return this;
  }

  public QueryServicesOptions setAllowOnlineSchemaUpdate(boolean allow) {
    config.setBoolean(ALLOW_ONLINE_TABLE_SCHEMA_UPDATE, allow);
    return this;
  }

  public QueryServicesOptions setNumRetriesForSchemaChangeCheck(int numRetries) {
    config.setInt(NUM_RETRIES_FOR_SCHEMA_UPDATE_CHECK, numRetries);
    return this;
  }

  public QueryServicesOptions setDelayInMillisForSchemaChangeCheck(long delayInMillis) {
    config.setLong(DELAY_FOR_SCHEMA_UPDATE_CHECK, delayInMillis);
    return this;

  }

  public QueryServicesOptions setUseByteBasedRegex(boolean flag) {
    config.setBoolean(USE_BYTE_BASED_REGEX_ATTRIB, flag);
    return this;
  }

  public QueryServicesOptions setForceRowKeyOrder(boolean forceRowKeyOrder) {
    config.setBoolean(FORCE_ROW_KEY_ORDER_ATTRIB, forceRowKeyOrder);
    return this;
  }

  public QueryServicesOptions setExtraJDBCArguments(String extraArgs) {
    config.set(EXTRA_JDBC_ARGUMENTS_ATTRIB, extraArgs);
    return this;
  }

  public QueryServicesOptions setRunUpdateStatsAsync(boolean flag) {
    config.setBoolean(RUN_UPDATE_STATS_ASYNC, flag);
    return this;
  }

  public QueryServicesOptions setCommitStatsAsync(boolean flag) {
    config.setBoolean(COMMIT_STATS_ASYNC, flag);
    return this;
  }

  public QueryServicesOptions setEnableRenewLease(boolean enable) {
    config.setBoolean(RENEW_LEASE_ENABLED, enable);
    return this;
  }

  public QueryServicesOptions setIndexHandlerCount(int count) {
    config.setInt(QueryServices.INDEX_HANDLER_COUNT_ATTRIB, count);
    return this;
  }

  public QueryServicesOptions setMetadataHandlerCount(int count) {
    config.setInt(QueryServices.METADATA_HANDLER_COUNT_ATTRIB, count);
    return this;
  }

  public QueryServicesOptions setHConnectionPoolCoreSize(int count) {
    config.setInt(QueryServices.HCONNECTION_POOL_CORE_SIZE, count);
    return this;
  }

  public QueryServicesOptions setHConnectionPoolMaxSize(int count) {
    config.setInt(QueryServices.HCONNECTION_POOL_MAX_SIZE, count);
    return this;
  }

  public QueryServicesOptions setMaxThreadsPerHTable(int count) {
    config.setInt(QueryServices.HTABLE_MAX_THREADS, count);
    return this;
  }

  public QueryServicesOptions setDefaultIndexPopulationWaitTime(long waitTime) {
    config.setLong(INDEX_POPULATION_SLEEP_TIME, waitTime);
    return this;
  }

  public QueryServicesOptions setUseStatsForParallelization(boolean flag) {
    config.setBoolean(USE_STATS_FOR_PARALLELIZATION, flag);
    return this;
  }

  public QueryServicesOptions setIndexRebuildTaskInitialDelay(long waitTime) {
    config.setLong(INDEX_REBUILD_TASK_INITIAL_DELAY, waitTime);
    return this;
  }

  public QueryServicesOptions setSequenceCacheSize(long sequenceCacheSize) {
    config.setLong(SEQUENCE_CACHE_SIZE_ATTRIB, sequenceCacheSize);
    return this;
  }
}
