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
package org.apache.phoenix.jdbc;

import static org.apache.phoenix.exception.SQLExceptionCode.CANNOT_DROP_CDC_INDEX;
import static org.apache.phoenix.monitoring.GlobalClientMetrics.GLOBAL_MUTATION_SQL_COUNTER;
import static org.apache.phoenix.monitoring.GlobalClientMetrics.GLOBAL_QUERY_TIME;
import static org.apache.phoenix.monitoring.GlobalClientMetrics.GLOBAL_SELECT_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.ATOMIC_UPSERT_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.ATOMIC_UPSERT_SQL_QUERY_TIME;
import static org.apache.phoenix.monitoring.MetricType.DELETE_AGGREGATE_FAILURE_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.DELETE_FAILED_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.DELETE_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.DELETE_SQL_QUERY_TIME;
import static org.apache.phoenix.monitoring.MetricType.DELETE_SUCCESS_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.MUTATION_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.SELECT_AGGREGATE_FAILURE_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.SELECT_FAILED_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.SELECT_POINTLOOKUP_FAILED_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.SELECT_POINTLOOKUP_SUCCESS_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.SELECT_SCAN_FAILED_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.SELECT_SCAN_SUCCESS_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.SELECT_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.SELECT_SQL_QUERY_TIME;
import static org.apache.phoenix.monitoring.MetricType.SELECT_SUCCESS_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.UPSERT_AGGREGATE_FAILURE_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.UPSERT_FAILED_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.UPSERT_SQL_COUNTER;
import static org.apache.phoenix.monitoring.MetricType.UPSERT_SQL_QUERY_TIME;
import static org.apache.phoenix.monitoring.MetricType.UPSERT_SUCCESS_SQL_COUNTER;
import static org.apache.phoenix.query.QueryServices.CONNECTION_EXPLAIN_PLAN_LOGGING_ENABLED;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.sql.BatchUpdateException;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.text.Format;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellComparator;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.client.Consistency;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.call.CallRunner;
import org.apache.phoenix.compile.BaseMutationPlan;
import org.apache.phoenix.compile.CloseStatementCompiler;
import org.apache.phoenix.compile.ColumnProjector;
import org.apache.phoenix.compile.CreateFunctionCompiler;
import org.apache.phoenix.compile.CreateIndexCompiler;
import org.apache.phoenix.compile.CreateSchemaCompiler;
import org.apache.phoenix.compile.CreateSequenceCompiler;
import org.apache.phoenix.compile.CreateTableCompiler;
import org.apache.phoenix.compile.DeclareCursorCompiler;
import org.apache.phoenix.compile.DeleteCompiler;
import org.apache.phoenix.compile.DropSequenceCompiler;
import org.apache.phoenix.compile.ExplainPlan;
import org.apache.phoenix.compile.ExplainPlanAttributes;
import org.apache.phoenix.compile.ExpressionProjector;
import org.apache.phoenix.compile.GroupByCompiler.GroupBy;
import org.apache.phoenix.compile.ListJarsQueryPlan;
import org.apache.phoenix.compile.MutationPlan;
import org.apache.phoenix.compile.OpenStatementCompiler;
import org.apache.phoenix.compile.OrderByCompiler.OrderBy;
import org.apache.phoenix.compile.QueryCompiler;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.RowProjector;
import org.apache.phoenix.compile.SequenceManager;
import org.apache.phoenix.compile.StatementContext;
import org.apache.phoenix.compile.StatementPlan;
import org.apache.phoenix.compile.TraceQueryPlan;
import org.apache.phoenix.compile.UpsertCompiler;
import org.apache.phoenix.coprocessorclient.MetaDataProtocol;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.exception.StaleMetadataCacheException;
import org.apache.phoenix.exception.UpgradeRequiredException;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.execute.MutationState.ReturnResult;
import org.apache.phoenix.execute.visitor.QueryPlanVisitor;
import org.apache.phoenix.expression.KeyValueColumnExpression;
import org.apache.phoenix.expression.RowKeyColumnExpression;
import org.apache.phoenix.iterate.ExplainTable;
import org.apache.phoenix.iterate.MaterializedResultIterator;
import org.apache.phoenix.iterate.ParallelScanGrouper;
import org.apache.phoenix.iterate.ResultIterator;
import org.apache.phoenix.log.ActivityLogInfo;
import org.apache.phoenix.log.AuditQueryLogger;
import org.apache.phoenix.log.LogLevel;
import org.apache.phoenix.log.QueryLogInfo;
import org.apache.phoenix.log.QueryLogger;
import org.apache.phoenix.log.QueryLoggerUtil;
import org.apache.phoenix.log.QueryStatus;
import org.apache.phoenix.monitoring.GlobalClientMetrics;
import org.apache.phoenix.monitoring.OverAllQueryMetrics;
import org.apache.phoenix.monitoring.TableMetricsManager;
import org.apache.phoenix.optimize.Cost;
import org.apache.phoenix.parse.AddColumnStatement;
import org.apache.phoenix.parse.AddJarsStatement;
import org.apache.phoenix.parse.AliasedNode;
import org.apache.phoenix.parse.AlterIndexStatement;
import org.apache.phoenix.parse.AlterSessionStatement;
import org.apache.phoenix.parse.BindableStatement;
import org.apache.phoenix.parse.ChangePermsStatement;
import org.apache.phoenix.parse.CloseStatement;
import org.apache.phoenix.parse.ColumnDef;
import org.apache.phoenix.parse.ColumnName;
import org.apache.phoenix.parse.CreateCDCStatement;
import org.apache.phoenix.parse.CreateFunctionStatement;
import org.apache.phoenix.parse.CreateIndexStatement;
import org.apache.phoenix.parse.CreateSchemaStatement;
import org.apache.phoenix.parse.CreateSequenceStatement;
import org.apache.phoenix.parse.CreateTableStatement;
import org.apache.phoenix.parse.CursorName;
import org.apache.phoenix.parse.DMLStatement;
import org.apache.phoenix.parse.DeclareCursorStatement;
import org.apache.phoenix.parse.DeleteJarStatement;
import org.apache.phoenix.parse.DeleteStatement;
import org.apache.phoenix.parse.DropCDCStatement;
import org.apache.phoenix.parse.DropColumnStatement;
import org.apache.phoenix.parse.DropFunctionStatement;
import org.apache.phoenix.parse.DropIndexStatement;
import org.apache.phoenix.parse.DropSchemaStatement;
import org.apache.phoenix.parse.DropSequenceStatement;
import org.apache.phoenix.parse.DropTableStatement;
import org.apache.phoenix.parse.ExecuteUpgradeStatement;
import org.apache.phoenix.parse.ExplainStatement;
import org.apache.phoenix.parse.ExplainType;
import org.apache.phoenix.parse.FetchStatement;
import org.apache.phoenix.parse.FilterableStatement;
import org.apache.phoenix.parse.HintNode;
import org.apache.phoenix.parse.IndexKeyConstraint;
import org.apache.phoenix.parse.LimitNode;
import org.apache.phoenix.parse.ListJarsStatement;
import org.apache.phoenix.parse.LiteralParseNode;
import org.apache.phoenix.parse.MutableStatement;
import org.apache.phoenix.parse.NamedNode;
import org.apache.phoenix.parse.NamedTableNode;
import org.apache.phoenix.parse.OffsetNode;
import org.apache.phoenix.parse.OpenStatement;
import org.apache.phoenix.parse.OrderByNode;
import org.apache.phoenix.parse.PFunction;
import org.apache.phoenix.parse.ParseNode;
import org.apache.phoenix.parse.ParseNodeFactory;
import org.apache.phoenix.parse.PrimaryKeyConstraint;
import org.apache.phoenix.parse.SQLParser;
import org.apache.phoenix.parse.SelectStatement;
import org.apache.phoenix.parse.ShowCreateTable;
import org.apache.phoenix.parse.ShowCreateTableStatement;
import org.apache.phoenix.parse.ShowSchemasStatement;
import org.apache.phoenix.parse.ShowTablesStatement;
import org.apache.phoenix.parse.TableName;
import org.apache.phoenix.parse.TableNode;
import org.apache.phoenix.parse.TraceStatement;
import org.apache.phoenix.parse.UDFParseNode;
import org.apache.phoenix.parse.UpdateStatisticsStatement;
import org.apache.phoenix.parse.UpsertStatement;
import org.apache.phoenix.parse.UseSchemaStatement;
import org.apache.phoenix.query.HBaseFactoryProvider;
import org.apache.phoenix.query.KeyRange;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.ExecuteQueryNotApplicableException;
import org.apache.phoenix.schema.ExecuteUpdateNotApplicableException;
import org.apache.phoenix.schema.FunctionNotFoundException;
import org.apache.phoenix.schema.MetaDataClient;
import org.apache.phoenix.schema.MetaDataEntityNotFoundException;
import org.apache.phoenix.schema.PColumnImpl;
import org.apache.phoenix.schema.PDatum;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PNameFactory;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.IndexType;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.RowKeyValueAccessor;
import org.apache.phoenix.schema.Sequence;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.TableRef;
import org.apache.phoenix.schema.stats.StatisticsCollectionScope;
import org.apache.phoenix.schema.tuple.MultiKeyValueTuple;
import org.apache.phoenix.schema.tuple.ResultTuple;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PLong;
import org.apache.phoenix.schema.types.PVarchar;
import org.apache.phoenix.trace.util.Tracing;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.CDCUtil;
import org.apache.phoenix.util.ClientUtil;
import org.apache.phoenix.util.CursorUtil;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.apache.phoenix.util.LogUtil;
import org.apache.phoenix.util.ParseNodeUtil;
import org.apache.phoenix.util.ParseNodeUtil.RewriteResult;
import org.apache.phoenix.util.PhoenixContextExecutor;
import org.apache.phoenix.util.PhoenixKeyValueUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.SQLCloseable;
import org.apache.phoenix.util.TupleUtil;
import org.apache.phoenix.util.ValidateLastDDLTimestampUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.base.Strings;
import org.apache.phoenix.thirdparty.com.google.common.base.Throwables;
import org.apache.phoenix.thirdparty.com.google.common.collect.ListMultimap;
import org.apache.phoenix.thirdparty.com.google.common.collect.Lists;
import org.apache.phoenix.thirdparty.com.google.common.math.IntMath;

/**
 * JDBC Statement implementation of Phoenix. Currently only the following methods are supported: -
 * {@link #executeQuery(String)} - {@link #executeUpdate(String)} - {@link #execute(String)} -
 * {@link #getResultSet()} - {@link #getUpdateCount()} - {@link #close()} The Statement only
 * supports the following options: - ResultSet.FETCH_FORWARD - ResultSet.TYPE_FORWARD_ONLY -
 * ResultSet.CLOSE_CURSORS_AT_COMMIT
 * @since 0.1
 */
public class PhoenixStatement implements PhoenixMonitoredStatement, SQLCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(PhoenixStatement.class);

  public enum Operation {
    QUERY("queried", false),
    DELETE("deleted", true),
    UPSERT("upserted", true),
    UPGRADE("upgrade", true),
    ADMIN("admin", true);

    private final String toString;
    private final boolean isMutation;

    Operation(String toString, boolean isMutation) {
      this.toString = toString;
      this.isMutation = isMutation;
    }

    public boolean isMutation() {
      return isMutation;
    }

    @Override
    public String toString() {
      return toString;
    }
  }

  protected final PhoenixConnection connection;
  private static final int NO_UPDATE = -1;
  private static final String TABLE_UNKNOWN = "";
  private QueryPlan lastQueryPlan;
  private ResultSet lastResultSet;
  private int lastUpdateCount = NO_UPDATE;

  private String lastUpdateTable = TABLE_UNKNOWN;
  private Operation lastUpdateOperation;
  private boolean isClosed = false;
  private boolean closeOnCompletion = false;
  private int maxRows;
  private int fetchSize = -1;
  private int queryTimeoutMillis;
  private boolean explainPlanLoggingEnabled;
  // Caching per Statement
  protected final Calendar localCalendar = Calendar.getInstance();
  private boolean validateLastDdlTimestamp;
  private long sqlQueryParsingTime = 0;

  public PhoenixStatement(PhoenixConnection connection) {
    this.connection = connection;
    this.queryTimeoutMillis = getDefaultQueryTimeoutMillis();
    this.validateLastDdlTimestamp =
      ValidateLastDDLTimestampUtil.getValidateLastDdlTimestampEnabled(this.connection);
    this.explainPlanLoggingEnabled =
      connection.getQueryServices().getProps().getBoolean(CONNECTION_EXPLAIN_PLAN_LOGGING_ENABLED,
        QueryServicesOptions.DEFAULT_CONNECTION_EXPLAIN_PLAN_LOGGING_ENABLED);
  }

  /**
   * Internally to Phoenix we allow callers to set the query timeout in millis via the
   * phoenix.query.timeoutMs. Therefore we store the time in millis.
   */
  private int getDefaultQueryTimeoutMillis() {
    return connection.getQueryServices().getProps().getInt(QueryServices.THREAD_TIMEOUT_MS_ATTRIB,
      QueryServicesOptions.DEFAULT_THREAD_TIMEOUT_MS);
  }

  private void setSqlQueryParsingTime(long time) {
    this.sqlQueryParsingTime = time;
  }

  private long getSqlQueryParsingTime() {
    return this.sqlQueryParsingTime;
  }

  public PhoenixResultSet newResultSet(ResultIterator iterator, RowProjector projector,
    StatementContext context) throws SQLException {
    return new PhoenixResultSet(iterator, projector, context);
  }

  protected QueryPlan optimizeQuery(CompilableStatement stmt) throws SQLException {
    QueryPlan plan = stmt.compilePlan(this, Sequence.ValueOp.VALIDATE_SEQUENCE);
    return connection.getQueryServices().getOptimizer().optimize(this, plan);
  }

  protected PhoenixResultSet executeQuery(final CompilableStatement stmt,
    final QueryLogger queryLogger) throws SQLException {
    return executeQuery(stmt, true, queryLogger, false, this.validateLastDdlTimestamp);
  }

  protected PhoenixResultSet executeQuery(final CompilableStatement stmt,
    final QueryLogger queryLogger, boolean noCommit) throws SQLException {
    return executeQuery(stmt, true, queryLogger, noCommit, this.validateLastDdlTimestamp);
  }

  private PhoenixResultSet executeQuery(final CompilableStatement stmt,
    final boolean doRetryOnMetaNotFoundError, final QueryLogger queryLogger, final boolean noCommit,
    boolean shouldValidateLastDdlTimestamp) throws SQLException {
    GLOBAL_SELECT_SQL_COUNTER.increment();

    try {
      return CallRunner.run(new CallRunner.CallableThrowable<PhoenixResultSet, SQLException>() {
        @Override
        public PhoenixResultSet call() throws SQLException {
          final long startTime = EnvironmentEdgeManager.currentTimeMillis();
          boolean success = false;
          boolean updateMetrics = true;
          boolean pointLookup = false;
          String tableName = null;
          clearResultSet();
          PhoenixResultSet rs = null;
          QueryPlan plan = null;
          OverAllQueryMetrics overallQuerymetrics;
          try {
            PhoenixConnection conn = getConnection();
            conn.checkOpen();

            if (
              conn.getQueryServices().isUpgradeRequired() && !conn.isRunningUpgrade()
                && stmt.getOperation() != Operation.UPGRADE
            ) {
              throw new UpgradeRequiredException();
            }
            final long queryPlanCreationStartTime = EnvironmentEdgeManager.currentTimeMillis();
            plan = stmt.compilePlan(PhoenixStatement.this, Sequence.ValueOp.VALIDATE_SEQUENCE);
            StatementContext context = plan.getContext();
            overallQuerymetrics = context.getOverallQueryMetrics();
            overallQuerymetrics.setQueryCompilerTimeMS(
              EnvironmentEdgeManager.currentTimeMillis() - queryPlanCreationStartTime);
            // Send mutations to hbase, so they are visible to subsequent reads.
            // Use original plan for data table so that data and immutable indexes will be sent
            // TODO: for joins, we need to iterate through all tables, but we need the original
            // table,
            // not the projected table, so plan.getContext().getResolver().getTables() won't work.
            if (plan.getContext().getScanRanges().isPointLookup()) {
              pointLookup = true;
            }
            Iterator<TableRef> tableRefs = plan.getSourceRefs().iterator();
            connection.getMutationState().sendUncommitted(tableRefs);
            final long queryOptimizerStartTime = EnvironmentEdgeManager.currentTimeMillis();
            plan =
              connection.getQueryServices().getOptimizer().optimize(PhoenixStatement.this, plan);
            overallQuerymetrics.setQueryOptimizerTimeMS(
              EnvironmentEdgeManager.currentTimeMillis() - queryOptimizerStartTime);
            setLastQueryPlan(plan);

            // verify metadata for the table/view/index in the query plan
            // plan.getTableRef can be null in some cases like EXPLAIN <query>
            if (shouldValidateLastDdlTimestamp && plan.getTableRef() != null) {
              ValidateLastDDLTimestampUtil.validateLastDDLTimestamp(connection,
                Arrays.asList(plan.getTableRef()), true);
            }

            if (
              plan.getTableRef() != null && plan.getTableRef().getTable() != null
                && !Strings
                  .isNullOrEmpty(plan.getTableRef().getTable().getPhysicalName().toString())
            ) {
              tableName = plan.getTableRef().getTable().getPhysicalName().toString();
            }
            final long queryResultItrSetStartTime = EnvironmentEdgeManager.currentTimeMillis();
            // this will create its own trace internally, so we don't wrap this
            // whole thing in tracing
            ResultIterator resultIterator = plan.iterator();
            overallQuerymetrics.setQueryResultItrTimeMS(
              EnvironmentEdgeManager.currentTimeMillis() - queryResultItrSetStartTime);
            if (LOGGER.isDebugEnabled()) {
              String explainPlan = QueryUtil.getExplainPlan(resultIterator);
              LOGGER
                .debug(LogUtil.addCustomAnnotations("Explain plan: " + explainPlan, connection));
            }
            if (explainPlanLoggingEnabled) {
              updateExplainPlanInformation(plan);
            }
            context.setQueryLogger(queryLogger);
            if (queryLogger.isDebugEnabled()) {
              queryLogger.log(QueryLogInfo.EXPLAIN_PLAN_I,
                QueryUtil.getExplainPlan(resultIterator));
              queryLogger.log(QueryLogInfo.GLOBAL_SCAN_DETAILS_I,
                context.getScan() != null ? context.getScan().toString() : null);
            }
            overallQuerymetrics.startQuery();
            overallQuerymetrics.setQueryParsingTimeMS(getSqlQueryParsingTime());
            rs = newResultSet(resultIterator, plan.getProjector(), plan.getContext());
            // newResultset sets lastResultset
            // ExecutableShowCreateTable/ExecutableShowTablesStatement/ExecutableShowSchemasStatement
            // using a delegateStmt
            // to compile a queryPlan, the resultSet will set to the delegateStmt, so need set
            // resultSet
            // to the origin statement.
            setLastResultSet(rs);
            setLastQueryPlan(plan);
            setLastUpdateCount(NO_UPDATE);
            setLastUpdateTable(tableName == null ? TABLE_UNKNOWN : tableName);
            setLastUpdateOperation(stmt.getOperation());
            // If transactional, this will move the read pointer forward
            if (connection.getAutoCommit() && !noCommit) {
              connection.commit();
            }
            connection.incrementStatementExecutionCounter();
            success = true;
          }
          // Force update cache and retry if meta not found error occurs
          catch (MetaDataEntityNotFoundException e) {
            if (doRetryOnMetaNotFoundError && e.getTableName() != null) {
              String sName = e.getSchemaName();
              String tName = e.getTableName();
              // when the query plan uses the local index PTable,
              // the TNFE can still be for the base table
              if (plan != null && plan.getTableRef() != null) {
                PTable queryPlanTable = plan.getTableRef().getTable();
                if (queryPlanTable != null && queryPlanTable.getIndexType() == IndexType.LOCAL) {
                  sName = queryPlanTable.getSchemaName().getString();
                  tName = queryPlanTable.getTableName().getString();
                }
              }
              if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Reloading table {} data from server", tName);
              }
              if (
                new MetaDataClient(connection)
                  .updateCache(connection.getTenantId(), sName, tName, true).wasUpdated()
              ) {
                updateMetrics = false;
                // TODO we can log retry count and error for debugging in LOG table
                return executeQuery(stmt, false, queryLogger, noCommit,
                  shouldValidateLastDdlTimestamp);
              }
            }
            throw e;
          } catch (StaleMetadataCacheException e) {
            GlobalClientMetrics.GLOBAL_CLIENT_STALE_METADATA_CACHE_EXCEPTION_COUNTER.increment();
            updateMetrics = false;
            PTable pTable = lastQueryPlan.getTableRef().getTable();
            String schemaN = pTable.getSchemaName().toString();
            String tableN = pTable.getTableName().toString();
            PName tenantId = connection.getTenantId();
            LOGGER.debug("Force updating client metadata cache for {}", ValidateLastDDLTimestampUtil
              .getInfoString(tenantId, Arrays.asList(getLastQueryPlan().getTableRef())));
            // force update client metadata cache for the table/view
            // this also updates the cache for all ancestors in case of a view
            new MetaDataClient(connection).updateCache(tenantId, schemaN, tableN, true);
            // skip last ddl timestamp validation in the retry
            return executeQuery(stmt, doRetryOnMetaNotFoundError, queryLogger, noCommit, false);
          } catch (RuntimeException e) {
            // FIXME: Expression.evaluate does not throw SQLException
            // so this will unwrap throws from that.
            if (e.getCause() instanceof SQLException) {
              throw (SQLException) e.getCause();
            }
            throw e;
          } finally {
            if (updateMetrics) {
              // Regardless of whether the query was successfully handled or not,
              // update the time spent so far. If needed, we can separate out the
              // success times and failure times.
              GLOBAL_QUERY_TIME.update(EnvironmentEdgeManager.currentTimeMillis() - startTime);
              long executeQueryTimeSpent = EnvironmentEdgeManager.currentTimeMillis() - startTime;
              if (tableName != null) {

                TableMetricsManager.updateMetricsMethod(tableName, SELECT_SQL_COUNTER, 1);
                TableMetricsManager.updateMetricsMethod(tableName, SELECT_SQL_QUERY_TIME,
                  executeQueryTimeSpent);
                if (success) {
                  TableMetricsManager.updateMetricsMethod(tableName, SELECT_SUCCESS_SQL_COUNTER, 1);
                  TableMetricsManager.updateMetricsMethod(tableName,
                    pointLookup
                      ? SELECT_POINTLOOKUP_SUCCESS_SQL_COUNTER
                      : SELECT_SCAN_SUCCESS_SQL_COUNTER,
                    1);
                } else {
                  TableMetricsManager.updateMetricsMethod(tableName, SELECT_FAILED_SQL_COUNTER, 1);
                  TableMetricsManager.updateMetricsMethod(tableName,
                    SELECT_AGGREGATE_FAILURE_SQL_COUNTER, 1);
                  TableMetricsManager.updateMetricsMethod(tableName,
                    pointLookup
                      ? SELECT_POINTLOOKUP_FAILED_SQL_COUNTER
                      : SELECT_SCAN_FAILED_SQL_COUNTER,
                    1);
                }
              }
              if (rs != null) {
                rs.setQueryTime(executeQueryTimeSpent);
              }
            }
          }
          return rs;
        }
      }, PhoenixContextExecutor.inContext());
    } catch (Exception e) {
      if (queryLogger.isDebugEnabled()) {
        queryLogger.log(QueryLogInfo.EXCEPTION_TRACE_I, Throwables.getStackTraceAsString(e));
        queryLogger.log(QueryLogInfo.QUERY_STATUS_I, QueryStatus.FAILED.toString());
        queryLogger.sync(null, null);
      }
      Throwables.propagateIfInstanceOf(e, SQLException.class);
      Throwables.propagate(e);
      throw new IllegalStateException(); // Can't happen as Throwables.propagate() always throws
    }
  }

  public String getTargetForAudit(CompilableStatement stmt) {
    String target = null;
    try {
      if (stmt instanceof ExecutableUpsertStatement) {
        return ((ExecutableUpsertStatement) stmt).getTable().getName().toString();
      } else if (stmt instanceof ExecutableDeleteStatement) {
        return ((ExecutableDeleteStatement) stmt).getTable().getName().toString();
      } else if (stmt instanceof ExecutableCreateTableStatement) {
        target = ((ExecutableCreateTableStatement) stmt).getTableName().toString();
      } else if (stmt instanceof ExecutableDropTableStatement) {
        target = ((ExecutableDropTableStatement) stmt).getTableName().toString();
      } else if (stmt instanceof ExecutableAddColumnStatement) {
        target = ((ExecutableAddColumnStatement) stmt).getTable().getName().toString();
      } else if (stmt instanceof ExecutableCreateSchemaStatement) {
        return ((ExecutableCreateSchemaStatement) stmt).getSchemaName();
      } else if (stmt instanceof ExecutableDropSchemaStatement) {
        target = ((ExecutableDropSchemaStatement) stmt).getSchemaName();
      }
    } catch (Exception e) {
      target = stmt.getClass().getName();
    }
    return target;
  }

  private boolean isResultSetExpected(final CompilableStatement stmt) {
    return stmt instanceof ExecutableUpsertStatement
      && ((ExecutableUpsertStatement) stmt).getOnDupKeyPairs() != null;
  }

  protected int executeMutation(final CompilableStatement stmt, final AuditQueryLogger queryLogger)
    throws SQLException {
    return executeMutation(stmt, true, queryLogger,
      isResultSetExpected(stmt) ? ReturnResult.NEW_ROW_ON_SUCCESS : null).getFirst();
  }

  Pair<Integer, ResultSet> executeMutation(final CompilableStatement stmt,
    final AuditQueryLogger queryLogger, final ReturnResult returnResult) throws SQLException {
    return executeMutation(stmt, true, queryLogger, returnResult);
  }

  private Pair<Integer, ResultSet> executeMutation(final CompilableStatement stmt,
    final boolean doRetryOnMetaNotFoundError, final AuditQueryLogger queryLogger,
    final ReturnResult returnResult) throws SQLException {
    if (connection.isReadOnly()) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.READ_ONLY_CONNECTION).build()
        .buildException();
    }
    GLOBAL_MUTATION_SQL_COUNTER.increment();
    try {
      return CallRunner
        .run(new CallRunner.CallableThrowable<Pair<Integer, ResultSet>, SQLException>() {
          @Override
          public Pair<Integer, ResultSet> call() throws SQLException {
            boolean success = false;
            String tableName = null;
            boolean isUpsert = false;
            boolean isAtomicUpsert = false;
            boolean isDelete = false;
            MutationState state = null;
            MutationPlan plan = null;
            final long startExecuteMutationTime = EnvironmentEdgeManager.currentTimeMillis();
            clearResultSet();
            try {
              PhoenixConnection conn = getConnection();
              if (
                conn.getQueryServices().isUpgradeRequired() && !conn.isRunningUpgrade()
                  && stmt.getOperation() != Operation.UPGRADE
              ) {
                throw new UpgradeRequiredException();
              }
              state = connection.getMutationState();
              state.setMutationQueryParsingTime(getSqlQueryParsingTime());
              isUpsert = stmt instanceof ExecutableUpsertStatement;
              isDelete = stmt instanceof ExecutableDeleteStatement;
              if (
                isDelete && connection.getAutoCommit()
                  && (returnResult == ReturnResult.NEW_ROW_ON_SUCCESS
                    || returnResult == ReturnResult.OLD_ROW_ALWAYS)
              ) {
                // used only if single row deletion needs to atomically
                // return row that is deleted.
                plan = ((ExecutableDeleteStatement) stmt).compilePlan(PhoenixStatement.this,
                  Sequence.ValueOp.VALIDATE_SEQUENCE, returnResult);
              } else {
                plan = stmt.compilePlan(PhoenixStatement.this, Sequence.ValueOp.VALIDATE_SEQUENCE);
              }
              isAtomicUpsert =
                isUpsert && ((ExecutableUpsertStatement) stmt).getOnDupKeyPairs() != null;
              if (plan.getTargetRef() != null && plan.getTargetRef().getTable() != null) {
                if (
                  !Strings
                    .isNullOrEmpty(plan.getTargetRef().getTable().getPhysicalName().toString())
                ) {
                  tableName = plan.getTargetRef().getTable().getPhysicalName().toString();
                }
                if (plan.getTargetRef().getTable().isTransactional()) {
                  state.startTransaction(plan.getTargetRef().getTable().getTransactionProvider());
                }
              }
              Iterator<TableRef> tableRefs = plan.getSourceRefs().iterator();
              state.sendUncommitted(tableRefs);
              state.checkpointIfNeccessary(plan);
              checkIfDDLStatementandMutationState(stmt, state);
              MutationState lastState = plan.execute();
              state.join(lastState);
              // Unfortunately, JDBC uses an int for update count, so we
              // just max out at Integer.MAX_VALUE
              int lastUpdateCount = (int) Math.min(Integer.MAX_VALUE, lastState.getUpdateCount());
              Result result = null;
              if (connection.getAutoCommit()) {
                boolean singleRowUpdate = isSingleRowUpdatePlan(isUpsert, isDelete, plan);
                if (singleRowUpdate) {
                  state.setReturnResult(returnResult);
                }
                connection.commit();
                if (isAtomicUpsert) {
                  lastUpdateCount = connection.getMutationState().getNumUpdatedRowsForAutoCommit();
                }
                result = connection.getMutationState().getResult();
                connection.getMutationState().clearResult();
                result = getResult(singleRowUpdate, isDelete, plan, lastState, result);
              }
              setLastQueryPlan(null);
              setLastUpdateCount(lastUpdateCount);
              setLastUpdateOperation(stmt.getOperation());
              setLastUpdateTable(tableName == null ? TABLE_UNKNOWN : tableName);
              connection.incrementStatementExecutionCounter();
              if (queryLogger.isAuditLoggingEnabled()) {
                queryLogger.log(QueryLogInfo.TABLE_NAME_I, getTargetForAudit(stmt));
                queryLogger.log(QueryLogInfo.QUERY_STATUS_I, QueryStatus.COMPLETED.toString());
                queryLogger.log(QueryLogInfo.NO_OF_RESULTS_ITERATED_I, lastUpdateCount);
                queryLogger.syncAudit();
              }
              success = true;
              TableName tableNameVal = isUpsert
                ? ((ExecutableUpsertStatement) stmt).getTable().getName()
                : (isDelete ? ((ExecutableDeleteStatement) stmt).getTable().getName() : null);
              ResultSet rs = result == null || result.isEmpty()
                ? null
                : TupleUtil.getResultSet(new ResultTuple(result), tableNameVal, connection);
              setLastResultSet(rs);
              return new Pair<>(lastUpdateCount, rs);
            }
            // Force update cache and retry if meta not found error occurs
            catch (MetaDataEntityNotFoundException e) {
              if (doRetryOnMetaNotFoundError && e.getTableName() != null) {
                if (LOGGER.isDebugEnabled()) {
                  LOGGER.debug("Reloading table {} data from server", e.getTableName());
                }
                if (
                  new MetaDataClient(connection).updateCache(connection.getTenantId(),
                    e.getSchemaName(), e.getTableName(), true).wasUpdated()
                ) {
                  return executeMutation(stmt, false, queryLogger, returnResult);
                }
              }
              throw e;
            } catch (RuntimeException e) {
              // FIXME: Expression.evaluate does not throw SQLException
              // so this will unwrap throws from that.
              if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
              }
              throw e;
            } finally {
              // Regardless of whether the mutation was successfully handled or not,
              // update the time spent so far. If needed, we can separate out the
              // success times and failure times.
              if (tableName != null) {
                // Counts for both ddl and dml
                TableMetricsManager.updateMetricsMethod(tableName, MUTATION_SQL_COUNTER, 1);
                // Only count dml operations
                if (isUpsert || isDelete) {
                  long executeMutationTimeSpent =
                    EnvironmentEdgeManager.currentTimeMillis() - startExecuteMutationTime;

                  TableMetricsManager.updateMetricsMethod(tableName,
                    isUpsert ? UPSERT_SQL_COUNTER : DELETE_SQL_COUNTER, 1);
                  TableMetricsManager.updateMetricsMethod(tableName,
                    isUpsert ? UPSERT_SQL_QUERY_TIME : DELETE_SQL_QUERY_TIME,
                    executeMutationTimeSpent);
                  if (isAtomicUpsert) {
                    TableMetricsManager.updateMetricsMethod(tableName, ATOMIC_UPSERT_SQL_COUNTER,
                      1);
                    TableMetricsManager.updateMetricsMethod(tableName, ATOMIC_UPSERT_SQL_QUERY_TIME,
                      executeMutationTimeSpent);
                  }

                  if (success) {
                    TableMetricsManager.updateMetricsMethod(tableName,
                      isUpsert ? UPSERT_SUCCESS_SQL_COUNTER : DELETE_SUCCESS_SQL_COUNTER, 1);
                  } else {
                    TableMetricsManager.updateMetricsMethod(tableName,
                      isUpsert ? UPSERT_FAILED_SQL_COUNTER : DELETE_FAILED_SQL_COUNTER, 1);
                    // Failures are updated for executeMutation phase and for autocommit=true case
                    // here.
                    TableMetricsManager.updateMetricsMethod(tableName,
                      isUpsert
                        ? UPSERT_AGGREGATE_FAILURE_SQL_COUNTER
                        : DELETE_AGGREGATE_FAILURE_SQL_COUNTER,
                      1);
                  }
                  if (
                    plan instanceof DeleteCompiler.ServerSelectDeleteMutationPlan
                      || plan instanceof UpsertCompiler.ServerUpsertSelectMutationPlan
                  ) {
                    TableMetricsManager.updateLatencyHistogramForMutations(tableName,
                      executeMutationTimeSpent, false);
                    // We won't have size histograms for delete mutations when auto commit is set to
                    // true and
                    // if plan is of ServerSelectDeleteMutationPlan or
                    // ServerUpsertSelectMutationPlan
                    // since the update happens on server.
                  } else {
                    state.addExecuteMutationTime(executeMutationTimeSpent, tableName);
                  }
                }
              }

            }
          }
        }, PhoenixContextExecutor.inContext(), Tracing.withTracing(connection, this.toString()));
    } catch (Exception e) {
      if (queryLogger.isAuditLoggingEnabled()) {
        queryLogger.log(QueryLogInfo.TABLE_NAME_I, getTargetForAudit(stmt));
        queryLogger.log(QueryLogInfo.EXCEPTION_TRACE_I, Throwables.getStackTraceAsString(e));
        queryLogger.log(QueryLogInfo.QUERY_STATUS_I, QueryStatus.FAILED.toString());
        queryLogger.syncAudit();
      }
      Throwables.propagateIfInstanceOf(e, SQLException.class);
      Throwables.propagate(e);
      throw new IllegalStateException(); // Can't happen as Throwables.propagate() always throws
    }
  }

  /**
   * Get different Result if the row is atomically deleted.
   * @param singleRowUpdate True if this is single row Upsert/Delete.
   * @param isDelete        True if this is Delete and not Upsert.
   * @param plan            Mutation Plan.
   * @param mutationState   Mutation State.
   * @param result          Result obtained so far.
   * @return Result for the atomically updated row.
   */
  private Result getResult(boolean singleRowUpdate, boolean isDelete, MutationPlan plan,
    MutationState mutationState, Result result) {
    if (
      singleRowUpdate && isDelete && plan instanceof DeleteCompiler.ServerSelectDeleteMutationPlan
    ) {
      result = mutationState.getResult();
    }
    return result;
  }

  private static boolean isSingleRowUpdatePlan(boolean isUpsert, boolean isDelete,
    MutationPlan plan) {
    boolean isSingleRowUpdate = false;
    if (isUpsert) {
      isSingleRowUpdate = true;
    } else if (isDelete) {
      isSingleRowUpdate = plan.getContext().getScanRanges().getPointLookupCount() == 1;
    }
    return isSingleRowUpdate;
  }

  protected static interface CompilableStatement extends BindableStatement {
    public <T extends StatementPlan> T compilePlan(PhoenixStatement stmt,
      Sequence.ValueOp seqAction) throws SQLException;
  }

  private static class ExecutableSelectStatement extends SelectStatement
    implements CompilableStatement {
    private ExecutableSelectStatement(TableNode from, HintNode hint, boolean isDistinct,
      List<AliasedNode> select, ParseNode where, List<ParseNode> groupBy, ParseNode having,
      List<OrderByNode> orderBy, LimitNode limit, OffsetNode offset, int bindCount,
      boolean isAggregate, boolean hasSequence, Map<String, UDFParseNode> udfParseNodes) {
      this(from, hint, isDistinct, select, where, groupBy, having, orderBy, limit, offset,
        bindCount, isAggregate, hasSequence, Collections.<SelectStatement> emptyList(),
        udfParseNodes);
    }

    private ExecutableSelectStatement(TableNode from, HintNode hint, boolean isDistinct,
      List<AliasedNode> select, ParseNode where, List<ParseNode> groupBy, ParseNode having,
      List<OrderByNode> orderBy, LimitNode limit, OffsetNode offset, int bindCount,
      boolean isAggregate, boolean hasSequence, List<SelectStatement> selects,
      Map<String, UDFParseNode> udfParseNodes) {
      super(from, hint, isDistinct, select, where, groupBy, having, orderBy, limit, offset,
        bindCount, isAggregate, hasSequence, selects, udfParseNodes);
    }

    private ExecutableSelectStatement(ExecutableSelectStatement select) {
      this(select.getFrom(), select.getHint(), select.isDistinct(), select.getSelect(),
        select.getWhere(), select.getGroupBy(), select.getHaving(), select.getOrderBy(),
        select.getLimit(), select.getOffset(), select.getBindCount(), select.isAggregate(),
        select.hasSequence(), select.getSelects(), select.getUdfParseNodes());
    }

    @SuppressWarnings("unchecked")
    @Override
    public QueryPlan compilePlan(PhoenixStatement phoenixStatement, Sequence.ValueOp seqAction)
      throws SQLException {
      if (!getUdfParseNodes().isEmpty()) {
        phoenixStatement.throwIfUnallowedUserDefinedFunctions(getUdfParseNodes());
      }

      RewriteResult rewriteResult = ParseNodeUtil.rewrite(this, phoenixStatement.getConnection());
      QueryPlan queryPlan = new QueryCompiler(phoenixStatement,
        rewriteResult.getRewrittenSelectStatement(), rewriteResult.getColumnResolver(),
        Collections.<PDatum> emptyList(), phoenixStatement.getConnection().getIteratorFactory(),
        new SequenceManager(phoenixStatement), true, false, null).compile();
      queryPlan.getContext().getSequenceManager().validateSequences(seqAction);
      return queryPlan;
    }

  }

  private static final byte[] EXPLAIN_PLAN_FAMILY = QueryConstants.SINGLE_COLUMN_FAMILY;
  private static final byte[] EXPLAIN_PLAN_COLUMN = PVarchar.INSTANCE.toBytes("Plan");
  private static final String EXPLAIN_PLAN_ALIAS = "PLAN";
  private static final String EXPLAIN_PLAN_TABLE_NAME = "PLAN_TABLE";
  private static final PDatum EXPLAIN_PLAN_DATUM = new PDatum() {
    @Override
    public boolean isNullable() {
      return true;
    }

    @Override
    public PDataType getDataType() {
      return PVarchar.INSTANCE;
    }

    @Override
    public Integer getMaxLength() {
      return null;
    }

    @Override
    public Integer getScale() {
      return null;
    }

    @Override
    public SortOrder getSortOrder() {
      return SortOrder.getDefault();
    }
  };
  private static final String EXPLAIN_PLAN_BYTES_ESTIMATE_COLUMN_NAME = "BytesEstimate";
  private static final byte[] EXPLAIN_PLAN_BYTES_ESTIMATE =
    PVarchar.INSTANCE.toBytes(EXPLAIN_PLAN_BYTES_ESTIMATE_COLUMN_NAME);
  public static final String EXPLAIN_PLAN_BYTES_ESTIMATE_COLUMN_ALIAS = "EST_BYTES_READ";
  private static final PColumnImpl EXPLAIN_PLAN_BYTES_ESTIMATE_COLUMN = new PColumnImpl(
    PNameFactory.newName(EXPLAIN_PLAN_BYTES_ESTIMATE), PNameFactory.newName(EXPLAIN_PLAN_FAMILY),
    PLong.INSTANCE, null, null, true, 1, SortOrder.getDefault(), 0, null, false, null, false, false,
    EXPLAIN_PLAN_BYTES_ESTIMATE, 0, false);

  private static final String EXPLAIN_PLAN_ROWS_ESTIMATE_COLUMN_NAME = "RowsEstimate";
  private static final byte[] EXPLAIN_PLAN_ROWS_ESTIMATE =
    PVarchar.INSTANCE.toBytes(EXPLAIN_PLAN_ROWS_ESTIMATE_COLUMN_NAME);
  public static final String EXPLAIN_PLAN_ROWS_COLUMN_ALIAS = "EST_ROWS_READ";
  private static final PColumnImpl EXPLAIN_PLAN_ROWS_ESTIMATE_COLUMN = new PColumnImpl(
    PNameFactory.newName(EXPLAIN_PLAN_ROWS_ESTIMATE), PNameFactory.newName(EXPLAIN_PLAN_FAMILY),
    PLong.INSTANCE, null, null, true, 2, SortOrder.getDefault(), 0, null, false, null, false, false,
    EXPLAIN_PLAN_ROWS_ESTIMATE, 0, false);

  private static final String EXPLAIN_PLAN_ESTIMATE_INFO_TS_COLUMN_NAME = "EstimateInfoTS";
  private static final byte[] EXPLAIN_PLAN_ESTIMATE_INFO_TS =
    PVarchar.INSTANCE.toBytes(EXPLAIN_PLAN_ESTIMATE_INFO_TS_COLUMN_NAME);
  public static final String EXPLAIN_PLAN_ESTIMATE_INFO_TS_COLUMN_ALIAS = "EST_INFO_TS";
  private static final PColumnImpl EXPLAIN_PLAN_ESTIMATE_INFO_TS_COLUMN = new PColumnImpl(
    PNameFactory.newName(EXPLAIN_PLAN_ESTIMATE_INFO_TS), PNameFactory.newName(EXPLAIN_PLAN_FAMILY),
    PLong.INSTANCE, null, null, true, 3, SortOrder.getDefault(), 0, null, false, null, false, false,
    EXPLAIN_PLAN_ESTIMATE_INFO_TS, 0, false);

  private static final RowProjector EXPLAIN_PLAN_ROW_PROJECTOR_WITH_BYTE_ROW_ESTIMATES =
    new RowProjector(Arrays.<ColumnProjector> asList(
      new ExpressionProjector(EXPLAIN_PLAN_ALIAS, EXPLAIN_PLAN_ALIAS, EXPLAIN_PLAN_TABLE_NAME,
        new RowKeyColumnExpression(EXPLAIN_PLAN_DATUM,
          new RowKeyValueAccessor(Collections.<PDatum> singletonList(EXPLAIN_PLAN_DATUM), 0)),
        false),
      new ExpressionProjector(EXPLAIN_PLAN_BYTES_ESTIMATE_COLUMN_ALIAS,
        EXPLAIN_PLAN_BYTES_ESTIMATE_COLUMN_ALIAS, EXPLAIN_PLAN_TABLE_NAME,
        new KeyValueColumnExpression(EXPLAIN_PLAN_BYTES_ESTIMATE_COLUMN), false),
      new ExpressionProjector(EXPLAIN_PLAN_ROWS_COLUMN_ALIAS, EXPLAIN_PLAN_ROWS_COLUMN_ALIAS,
        EXPLAIN_PLAN_TABLE_NAME, new KeyValueColumnExpression(EXPLAIN_PLAN_ROWS_ESTIMATE_COLUMN),
        false),
      new ExpressionProjector(EXPLAIN_PLAN_ESTIMATE_INFO_TS_COLUMN_ALIAS,
        EXPLAIN_PLAN_ESTIMATE_INFO_TS_COLUMN_ALIAS, EXPLAIN_PLAN_TABLE_NAME,
        new KeyValueColumnExpression(EXPLAIN_PLAN_ESTIMATE_INFO_TS_COLUMN), false)),
      0, true);

  private static class ExecutableExplainStatement extends ExplainStatement
    implements CompilableStatement {

    ExecutableExplainStatement(BindableStatement statement, ExplainType explainType) {
      super(statement, explainType);
    }

    @Override
    public CompilableStatement getStatement() {
      return (CompilableStatement) super.getStatement();
    }

    @Override
    public int getBindCount() {
      return getStatement().getBindCount();
    }

    @SuppressWarnings("unchecked")
    @Override
    public QueryPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      CompilableStatement compilableStmt = getStatement();
      StatementPlan compilePlan =
        compilableStmt.compilePlan(stmt, Sequence.ValueOp.VALIDATE_SEQUENCE);
      // if client is validating timestamps, ensure its metadata cache is up to date.
      if (ValidateLastDDLTimestampUtil.getValidateLastDdlTimestampEnabled(stmt.getConnection())) {
        Set<TableRef> tableRefs = compilePlan.getSourceRefs();
        for (TableRef tableRef : tableRefs) {
          new MetaDataClient(stmt.getConnection()).updateCache(stmt.getConnection().getTenantId(),
            tableRef.getTable().getSchemaName().getString(),
            tableRef.getTable().getTableName().getString(), true);
        }
        compilePlan = compilableStmt.compilePlan(stmt, Sequence.ValueOp.VALIDATE_SEQUENCE);
      }
      // For a QueryPlan, we need to get its optimized plan; for a MutationPlan, its enclosed
      // QueryPlan
      // has already been optimized during compilation.
      if (compilePlan instanceof QueryPlan) {
        QueryPlan dataPlan = (QueryPlan) compilePlan;
        compilePlan =
          stmt.getConnection().getQueryServices().getOptimizer().optimize(stmt, dataPlan);
      }
      final StatementPlan plan = compilePlan;
      List<String> planSteps = plan.getExplainPlan().getPlanSteps();
      ExplainType explainType = getExplainType();
      if (explainType == ExplainType.DEFAULT) {
        List<String> updatedExplainPlanSteps = new ArrayList<>(planSteps);
        updatedExplainPlanSteps.removeIf(
          planStep -> planStep != null && planStep.contains(ExplainTable.REGION_LOCATIONS));
        planSteps = Collections.unmodifiableList(updatedExplainPlanSteps);
      }
      List<Tuple> tuples = Lists.newArrayListWithExpectedSize(planSteps.size());
      Long estimatedBytesToScan = plan.getEstimatedBytesToScan();
      Long estimatedRowsToScan = plan.getEstimatedRowsToScan();
      Long estimateInfoTimestamp = plan.getEstimateInfoTimestamp();
      for (String planStep : planSteps) {
        byte[] row = PVarchar.INSTANCE.toBytes(planStep);
        List<Cell> cells = Lists.newArrayListWithCapacity(3);
        cells.add(PhoenixKeyValueUtil.newKeyValue(row, EXPLAIN_PLAN_FAMILY, EXPLAIN_PLAN_COLUMN,
          MetaDataProtocol.MIN_TABLE_TIMESTAMP, ByteUtil.EMPTY_BYTE_ARRAY));
        if (estimatedBytesToScan != null) {
          cells.add(
            PhoenixKeyValueUtil.newKeyValue(row, EXPLAIN_PLAN_FAMILY, EXPLAIN_PLAN_BYTES_ESTIMATE,
              MetaDataProtocol.MIN_TABLE_TIMESTAMP, PLong.INSTANCE.toBytes(estimatedBytesToScan)));
        }
        if (estimatedRowsToScan != null) {
          cells.add(
            PhoenixKeyValueUtil.newKeyValue(row, EXPLAIN_PLAN_FAMILY, EXPLAIN_PLAN_ROWS_ESTIMATE,
              MetaDataProtocol.MIN_TABLE_TIMESTAMP, PLong.INSTANCE.toBytes(estimatedRowsToScan)));
        }
        if (estimateInfoTimestamp != null) {
          cells.add(
            PhoenixKeyValueUtil.newKeyValue(row, EXPLAIN_PLAN_FAMILY, EXPLAIN_PLAN_ESTIMATE_INFO_TS,
              MetaDataProtocol.MIN_TABLE_TIMESTAMP, PLong.INSTANCE.toBytes(estimateInfoTimestamp)));
        }
        Collections.sort(cells, CellComparator.getInstance());
        Tuple tuple = new MultiKeyValueTuple(cells);
        tuples.add(tuple);
      }
      final Long estimatedBytes = estimatedBytesToScan;
      final Long estimatedRows = estimatedRowsToScan;
      final Long estimateTs = estimateInfoTimestamp;
      final ResultIterator iterator = new MaterializedResultIterator(tuples);
      return new QueryPlan() {

        @Override
        public ParameterMetaData getParameterMetaData() {
          return PhoenixParameterMetaData.EMPTY_PARAMETER_META_DATA;
        }

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("EXPLAIN PLAN"));
        }

        @Override
        public ResultIterator iterator() throws SQLException {
          return iterator;
        }

        @Override
        public ResultIterator iterator(ParallelScanGrouper scanGrouper) throws SQLException {
          return iterator;
        }

        @Override
        public ResultIterator iterator(ParallelScanGrouper scanGrouper, Scan scan)
          throws SQLException {
          return iterator;
        }

        @Override
        public long getEstimatedSize() {
          return 0;
        }

        @Override
        public Cost getCost() {
          return Cost.ZERO;
        }

        @Override
        public TableRef getTableRef() {
          return null;
        }

        @Override
        public Set<TableRef> getSourceRefs() {
          return Collections.emptySet();
        }

        @Override
        public RowProjector getProjector() {
          return EXPLAIN_PLAN_ROW_PROJECTOR_WITH_BYTE_ROW_ESTIMATES;
        }

        @Override
        public Integer getLimit() {
          return null;
        }

        @Override
        public Integer getOffset() {
          return null;
        }

        @Override
        public OrderBy getOrderBy() {
          return OrderBy.EMPTY_ORDER_BY;
        }

        @Override
        public GroupBy getGroupBy() {
          return GroupBy.EMPTY_GROUP_BY;
        }

        @Override
        public List<KeyRange> getSplits() {
          return Collections.emptyList();
        }

        @Override
        public List<List<Scan>> getScans() {
          return Collections.emptyList();
        }

        @Override
        public StatementContext getContext() {
          return plan.getContext();
        }

        @Override
        public FilterableStatement getStatement() {
          return null;
        }

        @Override
        public boolean isDegenerate() {
          return false;
        }

        @Override
        public boolean isRowKeyOrdered() {
          return true;
        }

        @Override
        public Operation getOperation() {
          return ExecutableExplainStatement.this.getOperation();
        }

        @Override
        public boolean useRoundRobinIterator() throws SQLException {
          return false;
        }

        @Override
        public <T> T accept(QueryPlanVisitor<T> visitor) {
          return visitor.defaultReturn(this);
        }

        @Override
        public Long getEstimatedRowsToScan() {
          return estimatedRows;
        }

        @Override
        public Long getEstimatedBytesToScan() {
          return estimatedBytes;
        }

        @Override
        public Long getEstimateInfoTimestamp() throws SQLException {
          return estimateTs;
        }

        @Override
        public List<OrderBy> getOutputOrderBys() {
          return Collections.<OrderBy> emptyList();
        }

        @Override
        public boolean isApplicable() {
          return true;
        }
      };
    }
  }

  private static class ExecutableUpsertStatement extends UpsertStatement
    implements CompilableStatement {

    private ExecutableUpsertStatement(NamedTableNode table, HintNode hintNode,
      List<ColumnName> columns, List<ParseNode> values, SelectStatement select, int bindCount,
      Map<String, UDFParseNode> udfParseNodes, List<Pair<ColumnName, ParseNode>> onDupKeyPairs,
      OnDuplicateKeyType onDupKeyType) {
      super(table, hintNode, columns, values, select, bindCount, udfParseNodes, onDupKeyPairs,
        onDupKeyType);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      if (!getUdfParseNodes().isEmpty()) {
        stmt.throwIfUnallowedUserDefinedFunctions(getUdfParseNodes());
      }
      UpsertCompiler compiler = new UpsertCompiler(stmt, this.getOperation());
      MutationPlan plan = compiler.compile(this);
      plan.getContext().getSequenceManager().validateSequences(seqAction);
      return plan;
    }
  }

  private static class ExecutableDeleteStatement extends DeleteStatement
    implements CompilableStatement {
    private ExecutableDeleteStatement(NamedTableNode table, HintNode hint, ParseNode whereNode,
      List<OrderByNode> orderBy, LimitNode limit, int bindCount,
      Map<String, UDFParseNode> udfParseNodes) {
      super(table, hint, whereNode, orderBy, limit, bindCount, udfParseNodes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      if (!getUdfParseNodes().isEmpty()) {
        stmt.throwIfUnallowedUserDefinedFunctions(getUdfParseNodes());
      }
      DeleteCompiler compiler = new DeleteCompiler(stmt, this.getOperation());
      MutationPlan plan = compiler.compile(this);
      plan.getContext().getSequenceManager().validateSequences(seqAction);
      return plan;
    }

    /**
     * Compile Plan for single row delete with additional condition on non-pk columns. New plan
     * compilation is used for returning the deleted row atomically only if it is deleted by the
     * given DELETE statement.
     * @param stmt         JDBC Phoenix Statement object.
     * @param seqAction    Sequence statement validation.
     * @param returnResult ReturnResult object.
     * @return The compiled MutationPlan.
     * @throws SQLException If something fails during plan compilation.
     */
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction,
      ReturnResult returnResult) throws SQLException {
      if (!getUdfParseNodes().isEmpty()) {
        stmt.throwIfUnallowedUserDefinedFunctions(getUdfParseNodes());
      }
      DeleteCompiler compiler = new DeleteCompiler(stmt, this.getOperation());
      MutationPlan plan = compiler.compile(this, returnResult);
      plan.getContext().getSequenceManager().validateSequences(seqAction);
      return plan;
    }
  }

  private static class ExecutableCreateTableStatement extends CreateTableStatement
    implements CompilableStatement {
    ExecutableCreateTableStatement(TableName tableName,
      ListMultimap<String, Pair<String, Object>> props, List<ColumnDef> columnDefs,
      PrimaryKeyConstraint pkConstraint, List<ParseNode> splitNodes, PTableType tableType,
      boolean ifNotExists, TableName baseTableName, ParseNode tableTypeIdNode, int bindCount,
      Boolean immutableRows, Map<String, Integer> familyCounters, boolean noVerify) {
      super(tableName, props, columnDefs, pkConstraint, splitNodes, tableType, ifNotExists,
        baseTableName, tableTypeIdNode, bindCount, immutableRows, familyCounters, noVerify);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      CreateTableCompiler compiler = new CreateTableCompiler(stmt, this.getOperation());
      return compiler.compile(this);
    }
  }

  private static class ExecutableCreateCDCStatement extends CreateCDCStatement
    implements CompilableStatement {
    public ExecutableCreateCDCStatement(NamedNode cdcObjName, TableName dataTable,
      Set<PTable.CDCChangeScope> includeScopes, ListMultimap<String, Pair<String, Object>> props,
      boolean ifNotExists, int bindCount) {
      super(cdcObjName, dataTable, includeScopes, props, ifNotExists, bindCount);
    }

    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("CREATE CDC"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.createCDC(ExecutableCreateCDCStatement.this);
        }
      };
    }
  }

  private static class ExecutableCreateSchemaStatement extends CreateSchemaStatement
    implements CompilableStatement {
    ExecutableCreateSchemaStatement(String schemaName, boolean ifNotExists) {
      super(schemaName, ifNotExists);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      CreateSchemaCompiler compiler = new CreateSchemaCompiler(stmt);
      return compiler.compile(this);
    }
  }

  private static class ExecutableCreateFunctionStatement extends CreateFunctionStatement
    implements CompilableStatement {

    public ExecutableCreateFunctionStatement(PFunction functionInfo, boolean temporary,
      boolean isReplace) {
      super(functionInfo, temporary, isReplace);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      stmt.throwIfUnallowedUserDefinedFunctions(Collections.EMPTY_MAP);
      CreateFunctionCompiler compiler = new CreateFunctionCompiler(stmt);
      return compiler.compile(this);
    }
  }

  private static class ExecutableDropFunctionStatement extends DropFunctionStatement
    implements CompilableStatement {

    public ExecutableDropFunctionStatement(String functionName, boolean ifNotExists) {
      super(functionName, ifNotExists);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ParameterMetaData getParameterMetaData() {
          return PhoenixParameterMetaData.EMPTY_PARAMETER_META_DATA;
        }

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("DROP FUNCTION"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.dropFunction(ExecutableDropFunctionStatement.this);
        }
      };
    }
  }

  private static class ExecutableAddJarsStatement extends AddJarsStatement
    implements CompilableStatement {

    public ExecutableAddJarsStatement(List<LiteralParseNode> jarPaths) {
      super(jarPaths);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new CustomMutationPlan(context, stmt);
    }

    private class CustomMutationPlan extends BaseMutationPlan {

      private final StatementContext context;
      private final PhoenixStatement stmt;

      private CustomMutationPlan(StatementContext context, PhoenixStatement stmt) {
        super(context, ExecutableAddJarsStatement.this.getOperation());
        this.context = context;
        this.stmt = stmt;
      }

      @Override
      public ParameterMetaData getParameterMetaData() {
        return PhoenixParameterMetaData.EMPTY_PARAMETER_META_DATA;
      }

      @Override
      public ExplainPlan getExplainPlan() throws SQLException {
        return new ExplainPlan(Collections.singletonList("ADD JARS"));
      }

      @Override
      public MutationState execute() throws SQLException {
        String dynamicJarsDir = stmt.getConnection().getQueryServices().getProps()
          .get(QueryServices.DYNAMIC_JARS_DIR_KEY);
        if (dynamicJarsDir == null) {
          throw new SQLException(
            QueryServices.DYNAMIC_JARS_DIR_KEY + " is not configured for placing the jars.");
        }
        dynamicJarsDir = dynamicJarsDir.endsWith("/") ? dynamicJarsDir : dynamicJarsDir + '/';
        Configuration conf = HBaseFactoryProvider.getConfigurationFactory().getConfiguration();
        Path dynamicJarsDirPath = new Path(dynamicJarsDir);
        for (LiteralParseNode jarPath : getJarPaths()) {
          String jarPathStr = (String) jarPath.getValue();
          if (!jarPathStr.endsWith(".jar")) {
            throw new SQLException(jarPathStr + " is not a valid jar file path.");
          }
        }
        try {
          FileSystem fs = dynamicJarsDirPath.getFileSystem(conf);
          List<LiteralParseNode> jarPaths = getJarPaths();
          for (LiteralParseNode jarPath : jarPaths) {
            File f = new File((String) jarPath.getValue());
            fs.copyFromLocalFile(new Path(f.getAbsolutePath()),
              new Path(dynamicJarsDir + f.getName()));
          }
        } catch (IOException e) {
          throw new SQLException(e);
        }
        return new MutationState(0, 0, context.getConnection());
      }
    }
  }

  private static class ExecutableDeclareCursorStatement extends DeclareCursorStatement
    implements CompilableStatement {
    public ExecutableDeclareCursorStatement(CursorName cursor, SelectStatement select) {
      super(cursor, select);
    }

    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      ExecutableSelectStatement wrappedSelect = new ExecutableSelectStatement(
        (ExecutableSelectStatement) stmt.parseStatement(this.getQuerySQL()));
      DeclareCursorCompiler compiler = new DeclareCursorCompiler(stmt, this.getOperation(),
        wrappedSelect.compilePlan(stmt, seqAction));
      return compiler.compile(this);
    }
  }

  private static class ExecutableOpenStatement extends OpenStatement
    implements CompilableStatement {
    public ExecutableOpenStatement(CursorName cursor) {
      super(cursor);
    }

    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      OpenStatementCompiler compiler = new OpenStatementCompiler(stmt, this.getOperation());
      return compiler.compile(this);
    }
  }

  private static class ExecutableCloseStatement extends CloseStatement
    implements CompilableStatement {
    public ExecutableCloseStatement(CursorName cursor) {
      super(cursor);
    }

    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      CloseStatementCompiler compiler = new CloseStatementCompiler(stmt, this.getOperation());
      return compiler.compile(this);
    }
  }

  private static class ExecutableFetchStatement extends FetchStatement
    implements CompilableStatement {
    public ExecutableFetchStatement(CursorName cursor, boolean isNext, int fetchLimit) {
      super(cursor, isNext, fetchLimit);
    }

    @Override
    public QueryPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      return CursorUtil.getFetchPlan(this.getCursorName().getName(), this.isNext(),
        this.getFetchSize());
    }

  }

  private static class ExecutableDeleteJarStatement extends DeleteJarStatement
    implements CompilableStatement {

    public ExecutableDeleteJarStatement(LiteralParseNode jarPath) {
      super(jarPath);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new CustomMutationPlan(context, stmt);
    }

    private class CustomMutationPlan extends BaseMutationPlan {

      private final StatementContext context;
      private final PhoenixStatement stmt;

      private CustomMutationPlan(StatementContext context, PhoenixStatement stmt) {
        super(context, ExecutableDeleteJarStatement.this.getOperation());
        this.context = context;
        this.stmt = stmt;
      }

      @Override
      public ParameterMetaData getParameterMetaData() {
        return PhoenixParameterMetaData.EMPTY_PARAMETER_META_DATA;
      }

      @Override
      public ExplainPlan getExplainPlan() throws SQLException {
        return new ExplainPlan(Collections.singletonList("DELETE JAR"));
      }

      @Override
      public MutationState execute() throws SQLException {
        String dynamicJarsDir = stmt.getConnection().getQueryServices().getProps()
          .get(QueryServices.DYNAMIC_JARS_DIR_KEY);
        if (dynamicJarsDir == null) {
          throw new SQLException(QueryServices.DYNAMIC_JARS_DIR_KEY + " is not configured.");
        }
        dynamicJarsDir = dynamicJarsDir.endsWith("/") ? dynamicJarsDir : dynamicJarsDir + '/';
        Configuration conf = HBaseFactoryProvider.getConfigurationFactory().getConfiguration();
        Path dynamicJarsDirPath = new Path(dynamicJarsDir);
        try {
          FileSystem fs = dynamicJarsDirPath.getFileSystem(conf);
          String jarPathStr = (String) getJarPath().getValue();
          if (!jarPathStr.endsWith(".jar")) {
            throw new SQLException(jarPathStr + " is not a valid jar file path.");
          }
          Path p = new Path(jarPathStr);
          if (fs.exists(p)) {
            fs.delete(p, false);
          }
        } catch (IOException e) {
          throw new SQLException(e);
        }
        return new MutationState(0, 0, context.getConnection());
      }
    }
  }

  private static class ExecutableListJarsStatement extends ListJarsStatement
    implements CompilableStatement {

    public ExecutableListJarsStatement() {
      super();
    }

    @SuppressWarnings("unchecked")
    @Override
    public QueryPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      return new ListJarsQueryPlan(stmt);
    }
  }

  private static class ExecutableShowTablesStatement extends ShowTablesStatement
    implements CompilableStatement {

    public ExecutableShowTablesStatement(String schema, String pattern) {
      super(schema, pattern);
    }

    @Override
    public QueryPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      PreparedStatement delegateStmt = QueryUtil.getTablesStmt(stmt.getConnection(), null,
        getTargetSchema(), getDbPattern(), null);
      return ((PhoenixPreparedStatement) delegateStmt).compileQuery();
    }
  }

  // Delegates to a SELECT query against SYSCAT.
  private static class ExecutableShowSchemasStatement extends ShowSchemasStatement
    implements CompilableStatement {

    public ExecutableShowSchemasStatement(String pattern) {
      super(pattern);
    }

    @Override
    public QueryPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      PreparedStatement delegateStmt =
        QueryUtil.getSchemasStmt(stmt.getConnection(), null, getSchemaPattern());
      return ((PhoenixPreparedStatement) delegateStmt).compileQuery();
    }
  }

  private static class ExecutableShowCreateTable extends ShowCreateTableStatement
    implements CompilableStatement {

    public ExecutableShowCreateTable(TableName tableName) {
      super(tableName);
    }

    @Override
    public QueryPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      PreparedStatement delegateStmt =
        QueryUtil.getShowCreateTableStmt(stmt.getConnection(), null, getTableName());
      return ((PhoenixPreparedStatement) delegateStmt).compileQuery();
    }
  }

  private static class ExecutableCreateIndexStatement extends CreateIndexStatement
    implements CompilableStatement {

    public ExecutableCreateIndexStatement(NamedNode indexName, NamedTableNode dataTable,
      IndexKeyConstraint ikConstraint, List<ColumnName> includeColumns, List<ParseNode> splits,
      ListMultimap<String, Pair<String, Object>> props, boolean ifNotExists, IndexType indexType,
      boolean async, int bindCount, Map<String, UDFParseNode> udfParseNodes, ParseNode where) {
      super(indexName, dataTable, ikConstraint, includeColumns, splits, props, ifNotExists,
        indexType, async, bindCount, udfParseNodes, where);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      if (!getUdfParseNodes().isEmpty()) {
        stmt.throwIfUnallowedUserDefinedFunctions(getUdfParseNodes());
      }
      CreateIndexCompiler compiler = new CreateIndexCompiler(stmt, this.getOperation());
      return compiler.compile(this);
    }
  }

  private static class ExecutableCreateSequenceStatement extends CreateSequenceStatement
    implements CompilableStatement {

    public ExecutableCreateSequenceStatement(TableName sequenceName, ParseNode startWith,
      ParseNode incrementBy, ParseNode cacheSize, ParseNode minValue, ParseNode maxValue,
      boolean cycle, boolean ifNotExists, int bindCount) {
      super(sequenceName, startWith, incrementBy, cacheSize, minValue, maxValue, cycle, ifNotExists,
        bindCount);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      CreateSequenceCompiler compiler = new CreateSequenceCompiler(stmt, this.getOperation());
      return compiler.compile(this);
    }
  }

  private static class ExecutableDropSequenceStatement extends DropSequenceStatement
    implements CompilableStatement {

    public ExecutableDropSequenceStatement(TableName sequenceName, boolean ifExists,
      int bindCount) {
      super(sequenceName, ifExists, bindCount);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      DropSequenceCompiler compiler = new DropSequenceCompiler(stmt, this.getOperation());
      return compiler.compile(this);
    }
  }

  private static class ExecutableDropTableStatement extends DropTableStatement
    implements CompilableStatement {

    ExecutableDropTableStatement(TableName tableName, PTableType tableType, boolean ifExists,
      boolean cascade) {
      super(tableName, tableType, ifExists, cascade, false);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("DROP TABLE"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.dropTable(ExecutableDropTableStatement.this);
        }
      };
    }
  }

  private static class ExecutableDropSchemaStatement extends DropSchemaStatement
    implements CompilableStatement {

    ExecutableDropSchemaStatement(String schemaName, boolean ifExists, boolean cascade) {
      super(schemaName, ifExists, cascade);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("DROP SCHEMA"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.dropSchema(ExecutableDropSchemaStatement.this);
        }
      };
    }
  }

  private static class ExecutableUseSchemaStatement extends UseSchemaStatement
    implements CompilableStatement {

    ExecutableUseSchemaStatement(String schemaName) {
      super(schemaName);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("USE SCHEMA"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.useSchema(ExecutableUseSchemaStatement.this);
        }
      };
    }
  }

  private static class ExecutableChangePermsStatement extends ChangePermsStatement
    implements CompilableStatement {

    public ExecutableChangePermsStatement(String permsString, boolean isSchemaName,
      TableName tableName, String schemaName, boolean isGroupName, LiteralParseNode userOrGroup,
      boolean isGrantStatement) {
      super(permsString, isSchemaName, tableName, schemaName, isGroupName, userOrGroup,
        isGrantStatement);
    }

    @Override
    public MutationPlan compilePlan(PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);

      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("GRANT PERMISSION"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.changePermissions(ExecutableChangePermsStatement.this);
        }
      };
    }
  }

  private static class ExecutableDropIndexStatement extends DropIndexStatement
    implements CompilableStatement {

    public ExecutableDropIndexStatement(NamedNode indexName, TableName tableName,
      boolean ifExists) {
      super(indexName, tableName, ifExists);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("DROP INDEX"));
        }

        @Override
        public MutationState execute() throws SQLException {
          String indexName = ExecutableDropIndexStatement.this.getIndexName().getName();
          if (CDCUtil.isCDCIndex(indexName)) {
            throw new SQLExceptionInfo.Builder(CANNOT_DROP_CDC_INDEX).setTableName(indexName)
              .build().buildException();
          }
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.dropIndex(ExecutableDropIndexStatement.this);
        }
      };
    }
  }

  private static class ExecutableDropCDCStatement extends DropCDCStatement
    implements CompilableStatement {

    public ExecutableDropCDCStatement(NamedNode cdcObjName, TableName tableName, boolean ifExists) {
      super(cdcObjName, tableName, ifExists);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("DROP CDC"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.dropCDC(ExecutableDropCDCStatement.this);
        }
      };
    }
  }

  private static class ExecutableAlterIndexStatement extends AlterIndexStatement
    implements CompilableStatement {

    public ExecutableAlterIndexStatement(NamedTableNode indexTableNode, String dataTableName,
      boolean ifExists, PIndexState state, boolean isRebuildAll, boolean async,
      ListMultimap<String, Pair<String, Object>> props) {
      super(indexTableNode, dataTableName, ifExists, state, isRebuildAll, async, props);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {
        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("ALTER INDEX"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.alterIndex(ExecutableAlterIndexStatement.this);
        }
      };
    }
  }

  private static class ExecutableTraceStatement extends TraceStatement
    implements CompilableStatement {

    public ExecutableTraceStatement(boolean isTraceOn, double samplingRate) {
      super(isTraceOn, samplingRate);
    }

    @SuppressWarnings("unchecked")
    @Override
    public QueryPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      return new TraceQueryPlan(this, stmt);
    }
  }

  private static class ExecutableAlterSessionStatement extends AlterSessionStatement
    implements CompilableStatement {

    public ExecutableAlterSessionStatement(Map<String, Object> props) {
      super(props);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new CustomMutationPlan(context);
    }

    private class CustomMutationPlan extends BaseMutationPlan {

      private final StatementContext context;

      private CustomMutationPlan(StatementContext context) {
        super(context, ExecutableAlterSessionStatement.this.getOperation());
        this.context = context;
      }

      @Override
      public StatementContext getContext() {
        return context;
      }

      @Override
      public ParameterMetaData getParameterMetaData() {
        return PhoenixParameterMetaData.EMPTY_PARAMETER_META_DATA;
      }

      @Override
      public ExplainPlan getExplainPlan() throws SQLException {
        return new ExplainPlan(Collections.singletonList("ALTER SESSION"));
      }

      @Override
      public MutationState execute() throws SQLException {
        Object consistency = getProps().get(PhoenixRuntime.CONSISTENCY_ATTRIB.toUpperCase());
        if (consistency != null) {
          if (((String) consistency).equalsIgnoreCase(Consistency.TIMELINE.toString())) {
            getContext().getConnection().setConsistency(Consistency.TIMELINE);
          } else {
            getContext().getConnection().setConsistency(Consistency.STRONG);
          }
        }
        return new MutationState(0, 0, context.getConnection());
      }
    }
  }

  private static class ExecutableUpdateStatisticsStatement extends UpdateStatisticsStatement
    implements CompilableStatement {
    public ExecutableUpdateStatisticsStatement(NamedTableNode table,
      StatisticsCollectionScope scope, Map<String, Object> props) {
      super(table, scope, props);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("UPDATE STATISTICS"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.updateStatistics(ExecutableUpdateStatisticsStatement.this);
        }
      };
    }

  }

  private static class ExecutableExecuteUpgradeStatement extends ExecuteUpgradeStatement
    implements CompilableStatement {
    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      return new MutationPlan() {

        @Override
        public Set<TableRef> getSourceRefs() {
          return Collections.emptySet();
        }

        @Override
        public ParameterMetaData getParameterMetaData() {
          return PhoenixParameterMetaData.EMPTY_PARAMETER_META_DATA;
        }

        @Override
        public Operation getOperation() {
          return Operation.UPGRADE;
        }

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(Collections.singletonList("EXECUTE UPGRADE"));
        }

        @Override
        public QueryPlan getQueryPlan() {
          return null;
        }

        @Override
        public StatementContext getContext() {
          return new StatementContext(stmt);
        }

        @Override
        public TableRef getTargetRef() {
          return TableRef.EMPTY_TABLE_REF;
        }

        @Override
        public MutationState execute() throws SQLException {
          PhoenixConnection phxConn = stmt.getConnection();
          Properties props = new Properties();
          phxConn.getQueryServices().upgradeSystemTables(phxConn.getURL(), props);
          return MutationState.emptyMutationState(-1, -1, phxConn);
        }

        @Override
        public Long getEstimatedRowsToScan() throws SQLException {
          return 0l;
        }

        @Override
        public Long getEstimatedBytesToScan() throws SQLException {
          return 0l;
        }

        @Override
        public Long getEstimateInfoTimestamp() throws SQLException {
          return 0l;
        }
      };
    }
  }

  private static class ExecutableAddColumnStatement extends AddColumnStatement
    implements CompilableStatement {

    ExecutableAddColumnStatement(NamedTableNode table, PTableType tableType,
      List<ColumnDef> columnDefs, boolean ifNotExists,
      ListMultimap<String, Pair<String, Object>> props, boolean cascade, List<NamedNode> indexes) {
      super(table, tableType, columnDefs, ifNotExists, props, cascade, indexes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(
            Collections.singletonList("ALTER " + getTableType() + " ADD COLUMN"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.addColumn(ExecutableAddColumnStatement.this);
        }
      };
    }
  }

  private static class ExecutableDropColumnStatement extends DropColumnStatement
    implements CompilableStatement {

    ExecutableDropColumnStatement(NamedTableNode table, PTableType tableType,
      List<ColumnName> columnRefs, boolean ifExists) {
      super(table, tableType, columnRefs, ifExists);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MutationPlan compilePlan(final PhoenixStatement stmt, Sequence.ValueOp seqAction)
      throws SQLException {
      final StatementContext context = new StatementContext(stmt);
      return new BaseMutationPlan(context, this.getOperation()) {

        @Override
        public ExplainPlan getExplainPlan() throws SQLException {
          return new ExplainPlan(
            Collections.singletonList("ALTER " + getTableType() + " DROP COLUMN"));
        }

        @Override
        public MutationState execute() throws SQLException {
          MetaDataClient client = new MetaDataClient(getContext().getConnection());
          return client.dropColumn(ExecutableDropColumnStatement.this);
        }
      };
    }
  }

  protected static class ExecutableNodeFactory extends ParseNodeFactory {
    @Override
    public ExecutableSelectStatement select(TableNode from, HintNode hint, boolean isDistinct,
      List<AliasedNode> select, ParseNode where, List<ParseNode> groupBy, ParseNode having,
      List<OrderByNode> orderBy, LimitNode limit, OffsetNode offset, int bindCount,
      boolean isAggregate, boolean hasSequence, List<SelectStatement> selects,
      Map<String, UDFParseNode> udfParseNodes) {
      return new ExecutableSelectStatement(from, hint, isDistinct, select, where,
        groupBy == null ? Collections.<ParseNode> emptyList() : groupBy, having,
        orderBy == null ? Collections.<OrderByNode> emptyList() : orderBy, limit, offset, bindCount,
        isAggregate, hasSequence,
        selects == null ? Collections.<SelectStatement> emptyList() : selects, udfParseNodes);
    }

    @Override
    public ExecutableUpsertStatement upsert(NamedTableNode table, HintNode hintNode,
      List<ColumnName> columns, List<ParseNode> values, SelectStatement select, int bindCount,
      Map<String, UDFParseNode> udfParseNodes, List<Pair<ColumnName, ParseNode>> onDupKeyPairs,
      UpsertStatement.OnDuplicateKeyType onDupKeyType) {
      return new ExecutableUpsertStatement(table, hintNode, columns, values, select, bindCount,
        udfParseNodes, onDupKeyPairs, onDupKeyType);
    }

    @Override
    public ExecutableDeclareCursorStatement declareCursor(CursorName cursor,
      SelectStatement select) {
      return new ExecutableDeclareCursorStatement(cursor, select);
    }

    @Override
    public ExecutableFetchStatement fetch(CursorName cursor, boolean isNext, int fetchLimit) {
      return new ExecutableFetchStatement(cursor, isNext, fetchLimit);
    }

    @Override
    public ExecutableOpenStatement open(CursorName cursor) {
      return new ExecutableOpenStatement(cursor);
    }

    @Override
    public ExecutableCloseStatement close(CursorName cursor) {
      return new ExecutableCloseStatement(cursor);
    }

    @Override
    public ExecutableDeleteStatement delete(NamedTableNode table, HintNode hint,
      ParseNode whereNode, List<OrderByNode> orderBy, LimitNode limit, int bindCount,
      Map<String, UDFParseNode> udfParseNodes) {
      return new ExecutableDeleteStatement(table, hint, whereNode, orderBy, limit, bindCount,
        udfParseNodes);
    }

    @Override
    public CreateTableStatement createTable(TableName tableName,
      ListMultimap<String, Pair<String, Object>> props, List<ColumnDef> columns,
      PrimaryKeyConstraint pkConstraint, List<ParseNode> splits, PTableType tableType,
      boolean ifNotExists, TableName baseTableName, ParseNode tableTypeIdNode, int bindCount,
      Boolean immutableRows, Map<String, Integer> cqCounters, boolean noVerify) {
      return new ExecutableCreateTableStatement(tableName, props, columns, pkConstraint, splits,
        tableType, ifNotExists, baseTableName, tableTypeIdNode, bindCount, immutableRows,
        cqCounters, noVerify);
    }

    @Override
    public CreateTableStatement createTable(TableName tableName,
      ListMultimap<String, Pair<String, Object>> props, List<ColumnDef> columns,
      PrimaryKeyConstraint pkConstraint, List<ParseNode> splits, PTableType tableType,
      boolean ifNotExists, TableName baseTableName, ParseNode tableTypeIdNode, int bindCount,
      Boolean immutableRows, Map<String, Integer> cqCounters) {
      return createTable(tableName, props, columns, pkConstraint, splits, tableType, ifNotExists,
        baseTableName, tableTypeIdNode, bindCount, immutableRows, cqCounters, false);
    }

    @Override
    public CreateTableStatement createTable(TableName tableName,
      ListMultimap<String, Pair<String, Object>> props, List<ColumnDef> columns,
      PrimaryKeyConstraint pkConstraint, List<ParseNode> splits, PTableType tableType,
      boolean ifNotExists, TableName baseTableName, ParseNode tableTypeIdNode, int bindCount,
      Boolean immutableRows) {
      return createTable(tableName, props, columns, pkConstraint, splits, tableType, ifNotExists,
        baseTableName, tableTypeIdNode, bindCount, immutableRows, null);
    }

    @Override
    public CreateCDCStatement createCDC(NamedNode cdcObj, TableName dataTable,
      Set<PTable.CDCChangeScope> includeScopes, ListMultimap<String, Pair<String, Object>> props,
      boolean ifNotExists, int bindCount) {
      return new ExecutableCreateCDCStatement(cdcObj, dataTable, includeScopes, props, ifNotExists,
        bindCount);
    }

    @Override
    public CreateSchemaStatement createSchema(String schemaName, boolean ifNotExists) {
      return new ExecutableCreateSchemaStatement(schemaName, ifNotExists);
    }

    @Override
    public CreateSequenceStatement createSequence(TableName tableName, ParseNode startsWith,
      ParseNode incrementBy, ParseNode cacheSize, ParseNode minValue, ParseNode maxValue,
      boolean cycle, boolean ifNotExists, int bindCount) {
      return new ExecutableCreateSequenceStatement(tableName, startsWith, incrementBy, cacheSize,
        minValue, maxValue, cycle, ifNotExists, bindCount);
    }

    @Override
    public CreateFunctionStatement createFunction(PFunction functionInfo, boolean temporary,
      boolean isReplace) {
      return new ExecutableCreateFunctionStatement(functionInfo, temporary, isReplace);
    }

    @Override
    public AddJarsStatement addJars(List<LiteralParseNode> jarPaths) {
      return new ExecutableAddJarsStatement(jarPaths);
    }

    @Override
    public DeleteJarStatement deleteJar(LiteralParseNode jarPath) {
      return new ExecutableDeleteJarStatement(jarPath);
    }

    @Override
    public ListJarsStatement listJars() {
      return new ExecutableListJarsStatement();
    }

    @Override
    public DropSequenceStatement dropSequence(TableName tableName, boolean ifExists,
      int bindCount) {
      return new ExecutableDropSequenceStatement(tableName, ifExists, bindCount);
    }

    @Override
    public CreateIndexStatement createIndex(NamedNode indexName, NamedTableNode dataTable,
      IndexKeyConstraint ikConstraint, List<ColumnName> includeColumns, List<ParseNode> splits,
      ListMultimap<String, Pair<String, Object>> props, boolean ifNotExists, IndexType indexType,
      boolean async, int bindCount, Map<String, UDFParseNode> udfParseNodes, ParseNode where) {
      return new ExecutableCreateIndexStatement(indexName, dataTable, ikConstraint, includeColumns,
        splits, props, ifNotExists, indexType, async, bindCount, udfParseNodes, where);
    }

    @Override
    public AddColumnStatement addColumn(NamedTableNode table, PTableType tableType,
      List<ColumnDef> columnDefs, boolean ifNotExists,
      ListMultimap<String, Pair<String, Object>> props, boolean cascade, List<NamedNode> indexes) {
      return new ExecutableAddColumnStatement(table, tableType, columnDefs, ifNotExists, props,
        cascade, indexes);
    }

    @Override
    public DropColumnStatement dropColumn(NamedTableNode table, PTableType tableType,
      List<ColumnName> columnNodes, boolean ifExists) {
      return new ExecutableDropColumnStatement(table, tableType, columnNodes, ifExists);
    }

    @Override
    public DropTableStatement dropTable(TableName tableName, PTableType tableType, boolean ifExists,
      boolean cascade) {
      return new ExecutableDropTableStatement(tableName, tableType, ifExists, cascade);
    }

    @Override
    public DropSchemaStatement dropSchema(String schemaName, boolean ifExists, boolean cascade) {
      return new ExecutableDropSchemaStatement(schemaName, ifExists, cascade);
    }

    @Override
    public UseSchemaStatement useSchema(String schemaName) {
      return new ExecutableUseSchemaStatement(schemaName);
    }

    @Override
    public DropFunctionStatement dropFunction(String functionName, boolean ifExists) {
      return new ExecutableDropFunctionStatement(functionName, ifExists);
    }

    @Override
    public DropIndexStatement dropIndex(NamedNode indexName, TableName tableName,
      boolean ifExists) {
      return new ExecutableDropIndexStatement(indexName, tableName, ifExists);
    }

    @Override
    public DropCDCStatement dropCDC(NamedNode cdcObjName, TableName tableName, boolean ifExists) {
      return new ExecutableDropCDCStatement(cdcObjName, tableName, ifExists);
    }

    @Override
    public AlterIndexStatement alterIndex(NamedTableNode indexTableNode, String dataTableName,
      boolean ifExists, PIndexState state, boolean isRebuildAll, boolean async,
      ListMultimap<String, Pair<String, Object>> props) {
      return new ExecutableAlterIndexStatement(indexTableNode, dataTableName, ifExists, state,
        isRebuildAll, async, props);
    }

    @Override
    public TraceStatement trace(boolean isTraceOn, double samplingRate) {
      return new ExecutableTraceStatement(isTraceOn, samplingRate);
    }

    @Override
    public AlterSessionStatement alterSession(Map<String, Object> props) {
      return new ExecutableAlterSessionStatement(props);
    }

    @Override
    public ExplainStatement explain(BindableStatement statement, ExplainType explainType) {
      return new ExecutableExplainStatement(statement, explainType);
    }

    @Override
    public UpdateStatisticsStatement updateStatistics(NamedTableNode table,
      StatisticsCollectionScope scope, Map<String, Object> props) {
      return new ExecutableUpdateStatisticsStatement(table, scope, props);
    }

    @Override
    public ExecuteUpgradeStatement executeUpgrade() {
      return new ExecutableExecuteUpgradeStatement();
    }

    @Override
    public ExecutableChangePermsStatement changePermsStatement(String permsString,
      boolean isSchemaName, TableName tableName, String schemaName, boolean isGroupName,
      LiteralParseNode userOrGroup, boolean isGrantStatement) {
      return new ExecutableChangePermsStatement(permsString, isSchemaName, tableName, schemaName,
        isGroupName, userOrGroup, isGrantStatement);
    }

    @Override
    public ShowTablesStatement showTablesStatement(String schema, String pattern) {
      return new ExecutableShowTablesStatement(schema, pattern);
    }

    @Override
    public ShowSchemasStatement showSchemasStatement(String pattern) {
      return new ExecutableShowSchemasStatement(pattern);
    }

    @Override
    public ShowCreateTable showCreateTable(TableName tableName) {
      return new ExecutableShowCreateTable(tableName);
    }

  }

  static class PhoenixStatementParser extends SQLParser {
    PhoenixStatementParser(String query, ParseNodeFactory nodeFactory) throws IOException {
      super(query, nodeFactory);
    }

    PhoenixStatementParser(Reader reader) throws IOException {
      super(reader);
    }

    @Override
    public CompilableStatement nextStatement(ParseNodeFactory nodeFactory) throws SQLException {
      return (CompilableStatement) super.nextStatement(nodeFactory);
    }

    @Override
    public CompilableStatement parseStatement() throws SQLException {
      return (CompilableStatement) super.parseStatement();
    }
  }

  public Format getFormatter(PDataType type) {
    return connection.getFormatter(type);
  }

  protected final List<PhoenixPreparedStatement> batch = Lists.newArrayList();

  @Override
  public void addBatch(String sql) throws SQLException {
    batch.add(new PhoenixPreparedStatement(connection, sql));
  }

  @Override
  public void clearBatch() throws SQLException {
    batch.clear();
  }

  /**
   * Execute the current batch of statements. If any exception occurs during execution, a
   * {@link java.sql.BatchUpdateException} is thrown which compposes the update counts for
   * statements executed so far.
   */
  @Override
  public int[] executeBatch() throws SQLException {
    int i = 0;
    int[] returnCodes = new int[batch.size()];
    Arrays.fill(returnCodes, -1);
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      for (i = 0; i < returnCodes.length; i++) {
        PhoenixPreparedStatement statement = batch.get(i);
        statement.executeForBatch();
        returnCodes[i] = statement.getUpdateCount();
      }
      // Flush all changes in batch if auto flush is true
      flushIfNecessary();
      // If we make it all the way through, clear the batch
      clearBatch();
      if (autoCommit) {
        connection.commit();
      }
      return returnCodes;
    } catch (SQLException t) {
      if (i == returnCodes.length) {
        // Exception after for loop, perhaps in commit(), discard returnCodes.
        throw new BatchUpdateException(t);
      } else {
        returnCodes[i] = Statement.EXECUTE_FAILED;
        throw new BatchUpdateException(returnCodes, t);
      }
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  @Override
  public void cancel() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void clearWarnings() throws SQLException {
  }

  @Override
  public void close() throws SQLException {
    try {
      clearResultSet();
    } finally {
      try {
        connection.removeStatement(this);
      } finally {
        isClosed = true;
      }
    }
  }

  // From the ResultSet javadoc:
  // A ResultSet object is automatically closed when the Statement object that generated it is
  // closed, re-executed, or used to retrieve the next result from a sequence of multiple results.
  private void clearResultSet() throws SQLException {
    if (lastResultSet != null) {
      try {
        lastResultSet.close();
      } finally {
        lastResultSet = null;
      }
    }
  }

  // Called from ResultSet.close(). rs is already closed.
  // We use a separate function to avoid calling close() again
  void removeResultSet(ResultSet rs) throws SQLException {
    if (rs == lastResultSet) {
      lastResultSet = null;
      if (closeOnCompletion) {
        this.close();
      }
    }
  }

  public List<Object> getParameters() {
    return Collections.<Object> emptyList();
  }

  protected CompilableStatement parseStatement(String sql) throws SQLException {
    long startQueryParsingTime = EnvironmentEdgeManager.currentTimeMillis();
    PhoenixStatementParser parser = null;
    try {
      parser = new PhoenixStatementParser(sql, new ExecutableNodeFactory());
    } catch (IOException e) {
      throw ClientUtil.parseServerException(e);
    }
    CompilableStatement statement = parser.parseStatement();
    setSqlQueryParsingTime(EnvironmentEdgeManager.currentTimeMillis() - startQueryParsingTime);
    return statement;
  }

  public QueryPlan optimizeQuery(String sql) throws SQLException {
    QueryPlan plan = compileQuery(sql);
    return connection.getQueryServices().getOptimizer().optimize(this, plan);
  }

  public QueryPlan compileQuery(String sql) throws SQLException {
    CompilableStatement stmt = parseStatement(sql);
    return compileQuery(stmt, sql);
  }

  public QueryPlan compileQuery(CompilableStatement stmt, String query) throws SQLException {
    if (stmt.getOperation().isMutation()) {
      throw new ExecuteQueryNotApplicableException(query);
    }
    return stmt.compilePlan(this, Sequence.ValueOp.VALIDATE_SEQUENCE);
  }

  public MutationPlan compileMutation(CompilableStatement stmt, String query) throws SQLException {
    if (!stmt.getOperation().isMutation()) {
      throw new ExecuteUpdateNotApplicableException(query);
    }
    return stmt.compilePlan(this, Sequence.ValueOp.VALIDATE_SEQUENCE);
  }

  public MutationPlan compileMutation(String sql) throws SQLException {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(LogUtil.addCustomAnnotations("Execute update: " + sql, connection));
    }
    CompilableStatement stmt = parseStatement(sql);
    return compileMutation(stmt, sql);
  }

  public boolean isSystemTable(CompilableStatement stmt) {
    boolean systemTable = false;
    TableName tableName = null;
    if (stmt instanceof ExecutableSelectStatement) {
      TableNode from = ((ExecutableSelectStatement) stmt).getFrom();
      if (from instanceof NamedTableNode) {
        tableName = ((NamedTableNode) from).getName();
      }
    } else if (stmt instanceof ExecutableUpsertStatement) {
      tableName = ((ExecutableUpsertStatement) stmt).getTable().getName();
    } else if (stmt instanceof ExecutableDeleteStatement) {
      tableName = ((ExecutableDeleteStatement) stmt).getTable().getName();
    } else if (stmt instanceof ExecutableAddColumnStatement) {
      tableName = ((ExecutableAddColumnStatement) stmt).getTable().getName();
    }

    if (
      tableName != null
        && PhoenixDatabaseMetaData.SYSTEM_CATALOG_SCHEMA.equals(tableName.getSchemaName())
    ) {
      systemTable = true;
    }

    return systemTable;
  }

  public QueryLogger createQueryLogger(CompilableStatement stmt, String sql) throws SQLException {
    if (connection.getLogLevel() == LogLevel.OFF) {
      return QueryLogger.NO_OP_INSTANCE;
    }

    QueryLogger queryLogger = QueryLogger.getInstance(connection, isSystemTable(stmt));
    QueryLoggerUtil.logInitialDetails(queryLogger, connection.getTenantId(),
      connection.getQueryServices(), sql, getParameters());
    return queryLogger;
  }

  public AuditQueryLogger createAuditQueryLogger(CompilableStatement stmt, String sql)
    throws SQLException {
    if (connection.getAuditLogLevel() == LogLevel.OFF) {
      return AuditQueryLogger.NO_OP_INSTANCE;
    }

    AuditQueryLogger queryLogger = AuditQueryLogger.getInstance(connection, isSystemTable(stmt));
    QueryLoggerUtil.logInitialDetails(queryLogger, connection.getTenantId(),
      connection.getQueryServices(), sql, getParameters());
    return queryLogger;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(LogUtil.addCustomAnnotations("Execute query: " + sql, connection));
    }

    CompilableStatement stmt = parseStatement(sql);
    if (stmt.getOperation().isMutation()) {
      throw new ExecuteQueryNotApplicableException(sql);
    }
    return executeQuery(stmt, createQueryLogger(stmt, sql));
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    CompilableStatement stmt = preExecuteUpdate(sql);
    int updateCount = executeMutation(stmt, createAuditQueryLogger(stmt, sql));
    flushIfNecessary();
    return updateCount;
  }

  /**
   * Executes the given SQL statement similar to JDBC API executeUpdate() but also returns the
   * updated or non-updated row as Result object back to the client. This must be used with
   * auto-commit Connection. This makes the operation atomic. If the row is successfully updated,
   * return the updated row, otherwise if the row cannot be updated, return non-updated row.
   * @param sql The SQL DML statement, UPSERT or DELETE for Phoenix.
   * @return The pair of int and ResultSet, where int represents value 1 for successful row update
   *         and 0 for non-successful row update, and ResultSet represents the state of the row.
   * @throws SQLException If the statement cannot be executed.
   */
  // Note: Do Not remove this, it is expected to be used by downstream applications
  public Pair<Integer, ResultSet> executeAtomicUpdateReturnRow(String sql) throws SQLException {
    if (!connection.getAutoCommit()) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.AUTO_COMMIT_NOT_ENABLED).build()
        .buildException();
    }
    CompilableStatement stmt = preExecuteUpdate(sql);
    Pair<Integer, ResultSet> result =
      executeMutation(stmt, createAuditQueryLogger(stmt, sql), ReturnResult.NEW_ROW_ON_SUCCESS);
    flushIfNecessary();
    return result;
  }

  /**
   * Executes the given SQL statement similar to JDBC API executeUpdate() but also returns the old
   * row (before update) as Result object back to the client. This must be used with auto-commit
   * Connection. This makes the operation atomic. Return the old row (state before update)
   * regardless of whether the update is successful or not.
   * @param sql The SQL DML statement, UPSERT or DELETE for Phoenix.
   * @return The pair of int and ResultSet, where int represents value 1 for successful row update
   *         and 0 for non-successful row update, and ResultSet represents the old state of the row.
   * @throws SQLException If the statement cannot be executed.
   */
  // Note: Do Not remove this, it is expected to be used by downstream applications
  public Pair<Integer, ResultSet> executeAtomicUpdateReturnOldRow(String sql) throws SQLException {
    if (!connection.getAutoCommit()) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.AUTO_COMMIT_NOT_ENABLED).build()
        .buildException();
    }
    CompilableStatement stmt = preExecuteUpdate(sql);
    Pair<Integer, ResultSet> result =
      executeMutation(stmt, createAuditQueryLogger(stmt, sql), ReturnResult.OLD_ROW_ALWAYS);
    flushIfNecessary();
    return result;
  }

  private CompilableStatement preExecuteUpdate(String sql) throws SQLException {
    CompilableStatement stmt = parseStatement(sql);
    if (!stmt.getOperation().isMutation) {
      throw new ExecuteUpdateNotApplicableException(sql);
    }
    if (!batch.isEmpty()) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.EXECUTE_UPDATE_WITH_NON_EMPTY_BATCH)
        .build().buildException();
    }
    return stmt;
  }

  private void flushIfNecessary() throws SQLException {
    if (connection.getAutoFlush()) {
      connection.flush();
    }
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    CompilableStatement stmt = parseStatement(sql);
    if (stmt.getOperation().isMutation()) {
      if (!batch.isEmpty()) {
        throw new SQLExceptionInfo.Builder(SQLExceptionCode.EXECUTE_UPDATE_WITH_NON_EMPTY_BATCH)
          .build().buildException();
      }
      executeMutation(stmt, createAuditQueryLogger(stmt, sql));
      flushIfNecessary();
      return isResultSetExpected(stmt);
    }

    executeQuery(stmt, createQueryLogger(stmt, sql));
    return true;
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    return execute(sql);
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    return execute(sql);
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    return execute(sql);
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public PhoenixConnection getConnection() {
    return connection;
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public int getFetchSize() throws SQLException {
    if (fetchSize > 0) {
      return fetchSize;
    } else {
      return connection.getQueryServices().getProps().getInt(QueryServices.SCAN_CACHE_SIZE_ATTRIB,
        QueryServicesOptions.DEFAULT_SCAN_CACHE_SIZE);
    }
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    return 0; // TODO: 4000?
  }

  @Override
  public int getMaxRows() throws SQLException {
    return maxRows;
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return false;
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    return false;
  }

  // For testing
  public QueryPlan getQueryPlan() {
    return getLastQueryPlan();
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    ResultSet rs = getLastResultSet();
    return rs;
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    // TODO: not sure this matters
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getResultSetType() throws SQLException {
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public Operation getUpdateOperation() {
    return getLastUpdateOperation();
  }

  @Override
  public int getUpdateCount() throws SQLException {
    int updateCount = getLastUpdateCount();
    // Only first call can get the update count, otherwise
    // some SQL clients get into an infinite loop when an
    // update occurs.
    setLastUpdateCount(NO_UPDATE);
    return updateCount;
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return null;
  }

  @Override
  public boolean isClosed() throws SQLException {
    return isClosed;
  }

  @Override
  public boolean isPoolable() throws SQLException {
    return false;
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    // TODO: any escaping we need to do?
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    if (direction != ResultSet.FETCH_FORWARD) {
      throw new SQLFeatureNotSupportedException();
    }
  }

  @Override
  public void setFetchSize(int fetchSize) throws SQLException {
    // TODO: map to Scan.setBatch() ?
    this.fetchSize = fetchSize;
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    this.maxRows = max;
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    if (poolable) {
      throw new SQLFeatureNotSupportedException();
    }
  }

  @Override
  /**
   * When setting the query timeout via JDBC timeouts must be expressed in seconds. Therefore we
   * need to convert the default timeout to milliseconds for internal use.
   */
  public void setQueryTimeout(int seconds) throws SQLException {
    if (seconds < 0) {
      this.queryTimeoutMillis = getDefaultQueryTimeoutMillis();
    } else if (seconds == 0) {
      this.queryTimeoutMillis = Integer.MAX_VALUE;
    } else {
      this.queryTimeoutMillis = seconds * 1000;
    }
  }

  @Override
  /**
   * When getting the query timeout via JDBC timeouts must be expressed in seconds. Therefore we
   * need to convert the default millisecond timeout to seconds.
   */
  public int getQueryTimeout() throws SQLException {
    // Convert milliseconds to seconds by taking the CEIL up to the next second
    int scaledValue;
    try {
      scaledValue = IntMath.checkedAdd(queryTimeoutMillis, 999);
    } catch (ArithmeticException e) {
      scaledValue = Integer.MAX_VALUE;
    }
    return scaledValue / 1000;
  }

  /**
   * Returns the configured timeout in milliseconds. This internally enables the of use millisecond
   * timeout granularity and honors the exact value configured by phoenix.query.timeoutMs.
   */
  public int getQueryTimeoutInMillis() {
    return queryTimeoutMillis;
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isInstance(this);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (!iface.isInstance(this)) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.CLASS_NOT_UNWRAPPABLE)
        .setMessage(this.getClass().getName() + " not unwrappable from " + iface.getName()).build()
        .buildException();
    }
    return (T) this;
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    closeOnCompletion = true;
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    return closeOnCompletion;
  }

  private ResultSet getLastResultSet() {
    return lastResultSet;
  }

  void setLastResultSet(ResultSet lastResultSet) {
    this.lastResultSet = lastResultSet;
  }

  private int getLastUpdateCount() {
    return lastUpdateCount;
  }

  private void setLastUpdateCount(int lastUpdateCount) {
    this.lastUpdateCount = lastUpdateCount;
  }

  private String getLastUpdateTable() {
    return lastUpdateTable;
  }

  private void setLastUpdateTable(String lastUpdateTable) {
    if (!Strings.isNullOrEmpty(lastUpdateTable)) {
      this.lastUpdateTable = lastUpdateTable;
    }
    if (
      getConnection().getActivityLogger().isLevelEnabled(ActivityLogInfo.TABLE_NAME.getLogLevel())
    ) {
      updateActivityOnConnection(ActivityLogInfo.TABLE_NAME, this.lastUpdateTable);
    }
  }

  private Operation getLastUpdateOperation() {
    return lastUpdateOperation;
  }

  private void setLastUpdateOperation(Operation lastUpdateOperation) {
    this.lastUpdateOperation = lastUpdateOperation;
    if (getConnection().getActivityLogger().isLevelEnabled(ActivityLogInfo.OP_NAME.getLogLevel())) {
      updateActivityOnConnection(ActivityLogInfo.OP_NAME, this.lastUpdateOperation.toString());
    }
    if (getConnection().getActivityLogger().isLevelEnabled(ActivityLogInfo.OP_TIME.getLogLevel())) {
      updateActivityOnConnection(ActivityLogInfo.OP_TIME,
        String.valueOf(EnvironmentEdgeManager.currentTimeMillis()));
    }
  }

  private QueryPlan getLastQueryPlan() {
    return lastQueryPlan;
  }

  private void setLastQueryPlan(QueryPlan lastQueryPlan) {
    this.lastQueryPlan = lastQueryPlan;

  }

  private void updateActivityOnConnection(ActivityLogInfo item, String value) {
    getConnection().getActivityLogger().log(item, value);
  }

  private void throwIfUnallowedUserDefinedFunctions(Map<String, UDFParseNode> udfParseNodes)
    throws SQLException {
    if (
      !connection.getQueryServices().getProps().getBoolean(
        QueryServices.ALLOW_USER_DEFINED_FUNCTIONS_ATTRIB,
        QueryServicesOptions.DEFAULT_ALLOW_USER_DEFINED_FUNCTIONS)
    ) {
      if (udfParseNodes.isEmpty()) {
        throw new SQLExceptionInfo.Builder(SQLExceptionCode.UNALLOWED_USER_DEFINED_FUNCTIONS)
          .build().buildException();
      }
      throw new FunctionNotFoundException(udfParseNodes.keySet().toString());
    }
  }

  /**
   * Check if the statement is a DDL and if there are any uncommitted mutations Throw or log the
   * message
   */
  private void checkIfDDLStatementandMutationState(final CompilableStatement stmt,
    MutationState state) throws SQLException {
    boolean throwUncommittedMutation = connection.getQueryServices().getProps().getBoolean(
      QueryServices.PENDING_MUTATIONS_DDL_THROW_ATTRIB,
      QueryServicesOptions.DEFAULT_PENDING_MUTATIONS_DDL_THROW);
    if (
      stmt instanceof MutableStatement && !(stmt instanceof DMLStatement) && state.getNumRows() > 0
    ) {
      if (throwUncommittedMutation) {
        throw new SQLExceptionInfo.Builder(
          SQLExceptionCode.CANNOT_PERFORM_DDL_WITH_PENDING_MUTATIONS).build().buildException();
      } else {
        LOGGER.warn(
          "There are Uncommitted mutations, which will be dropped on the execution of this DDL statement.");
      }
    }
  }

  public Calendar getLocalCalendar() {
    return localCalendar;
  }

  private void updateExplainPlanInformation(QueryPlan plan) throws SQLException {
    if (
      plan == null || !getConnection().getActivityLogger()
        .isLevelEnabled(ActivityLogInfo.EXPLAIN_PLAN.getLogLevel())
    ) {
      return;
    }

    ExplainPlan explainPlan = plan.getExplainPlan();
    ExplainPlanAttributes explainPlanAttributes = explainPlan.getPlanStepsAsAttributes();

    List<HRegionLocation> location = explainPlanAttributes.getRegionLocations();
    String regionInfo = getRegionInfo(location);

    String sb = Stream.of(explainPlanAttributes.getExplainScanType(), regionInfo)
      .collect(Collectors.joining(","));
    updateActivityOnConnection(ActivityLogInfo.EXPLAIN_PLAN, sb);
  }

  private String getRegionInfo(List<HRegionLocation> location) {
    if (location == null || location.isEmpty()) {
      return "";
    }

    String regions =
      location.stream().map(regionLocation -> regionLocation.getRegion().getEncodedName())
        .collect(Collectors.joining(","));

    String hostnames =
      location.stream().map(HRegionLocation::getHostname).collect(Collectors.joining(","));

    return QueryUtil.REGIONS + "={" + regions + "}," + QueryUtil.HOSTNAMES + "={" + hostnames + "}";
  }

}
