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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.compile.BindManager;
import org.apache.phoenix.compile.MutationPlan;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.StatementPlan;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.schema.ExecuteQueryNotApplicableException;
import org.apache.phoenix.schema.ExecuteUpdateNotApplicableException;
import org.apache.phoenix.schema.Sequence;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.util.DateUtil;
import org.apache.phoenix.util.SQLCloseable;

/**
 * JDBC PreparedStatement implementation of Phoenix. Currently only the following methods (in
 * addition to the ones supported on {@link PhoenixStatement} are supported: -
 * {@link #executeQuery()} - {@link #setInt(int, int)} - {@link #setShort(int, short)} -
 * {@link #setLong(int, long)} - {@link #setFloat(int, float)} - {@link #setDouble(int, double)} -
 * {@link #setBigDecimal(int, BigDecimal)} - {@link #setString(int, String)} -
 * {@link #setDate(int, Date)} - {@link #setDate(int, Date, Calendar)} - {@link #setTime(int, Time)}
 * - {@link #setTime(int, Time, Calendar)} - {@link #setTimestamp(int, Timestamp)} -
 * {@link #setTimestamp(int, Timestamp, Calendar)} - {@link #setNull(int, int)} -
 * {@link #setNull(int, int, String)} - {@link #setBytes(int, byte[])} - {@link #clearParameters()}
 * - {@link #getMetaData()}
 * @since 0.1
 */
public class PhoenixPreparedStatement extends PhoenixStatement
  implements PhoenixMonitoredPreparedStatement, SQLCloseable {
  private final int parameterCount;
  private final List<Object> parameters;
  private final CompilableStatement statement;

  private final String query;

  public PhoenixPreparedStatement(PhoenixConnection connection, PhoenixStatementParser parser)
    throws SQLException, IOException {
    super(connection);
    this.statement = parser.nextStatement(new ExecutableNodeFactory());
    if (this.statement == null) {
      throw new EOFException();
    }
    this.query = null; // TODO: add toString on SQLStatement
    this.parameterCount = statement.getBindCount();
    this.parameters = Arrays.asList(new Object[statement.getBindCount()]);
    Collections.fill(parameters, BindManager.UNBOUND_PARAMETER);
  }

  public PhoenixPreparedStatement(PhoenixConnection connection, String query) throws SQLException {
    super(connection);
    this.query = query;
    this.statement = parseStatement(query);
    this.parameterCount = statement.getBindCount();
    this.parameters = Arrays.asList(new Object[statement.getBindCount()]);
    Collections.fill(parameters, BindManager.UNBOUND_PARAMETER);
  }

  public PhoenixPreparedStatement(PhoenixPreparedStatement statement) {
    super(statement.connection);
    this.query = statement.query;
    this.statement = statement.statement;
    this.parameterCount = statement.parameters.size();
    this.parameters = new ArrayList<Object>(statement.parameters);
  }

  @Override
  public void addBatch() throws SQLException {
    throwIfUnboundParameters();
    batch.add(new PhoenixPreparedStatement(this));
  }

  /**
   * Set a bind parameter's value.
   * @param parameterIndex 1-based index of the bind parameter to be set
   * @param value          value to be set
   * @throws SQLException if the bind parameter index is invalid
   */
  private void setParameter(int parameterIndex, Object value) throws SQLException {
    if (parameterIndex < 1 || parameterIndex > parameterCount) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.PARAM_INDEX_OUT_OF_BOUND)
        .setMessage("Can't set parameter at index " + parameterIndex + ", " + parameterCount
          + " bind parameters are defined")
        .build().buildException();
    }
    this.parameters.set(parameterIndex - 1, value);
  }

  @Override
  public void clearParameters() throws SQLException {
    Collections.fill(parameters, BindManager.UNBOUND_PARAMETER);
  }

  @Override
  public List<Object> getParameters() {
    return parameters;
  }

  private void throwIfUnboundParameters() throws SQLException {
    int i = 0;
    for (Object param : getParameters()) {
      if (param == BindManager.UNBOUND_PARAMETER) {
        throw new SQLExceptionInfo.Builder(SQLExceptionCode.PARAM_VALUE_UNBOUND)
          .setMessage("Parameter " + (i + 1) + " is unbound").build().buildException();
      }
      i++;
    }
  }

  public QueryPlan compileQuery() throws SQLException {
    return compileQuery(statement, query);
  }

  public MutationPlan compileMutation() throws SQLException {
    return compileMutation(statement, query);
  }

  void executeForBatch() throws SQLException {
    throwIfUnboundParameters();
    if (!statement.getOperation().isMutation()) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.EXECUTE_BATCH_FOR_STMT_WITH_RESULT_SET)
        .build().buildException();
    }
    executeMutation(statement, createAuditQueryLogger(statement, query));
  }

  @Override
  public boolean execute() throws SQLException {
    throwIfUnboundParameters();
    if (statement.getOperation().isMutation() && !batch.isEmpty()) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.EXECUTE_UPDATE_WITH_NON_EMPTY_BATCH)
        .build().buildException();
    }
    if (statement.getOperation().isMutation()) {
      executeMutation(statement, createAuditQueryLogger(statement, query));
      return false;
    }
    executeQuery(statement, createQueryLogger(statement, query));
    return true;
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    throwIfUnboundParameters();
    if (statement.getOperation().isMutation()) {
      throw new ExecuteQueryNotApplicableException(statement.getOperation());
    }

    return executeQuery(statement, createQueryLogger(statement, query));
  }

  @Override
  public int executeUpdate() throws SQLException {
    preExecuteUpdate();
    return executeMutation(statement, createAuditQueryLogger(statement, query));
  }

  private void preExecuteUpdate() throws SQLException {
    throwIfUnboundParameters();
    if (!statement.getOperation().isMutation()) {
      throw new ExecuteUpdateNotApplicableException(statement.getOperation());
    }
    if (!batch.isEmpty()) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.EXECUTE_UPDATE_WITH_NON_EMPTY_BATCH)
        .build().buildException();
    }
  }

  /**
   * Executes the given SQL statement similar to JDBC API executeUpdate() but also returns the old
   * row (before update) as Result object back to the client. This must be used with auto-commit
   * Connection. This makes the operation atomic. Return the old row (state before update)
   * regardless of whether the update is successful or not.
   * @return The pair of int and ResultSet, where int represents value 1 for successful row update
   *         and 0 for non-successful row update, and ResultSet represents the old state of the row.
   * @throws SQLException If the statement cannot be executed.
   */
  // Note: Do Not remove this, it is expected to be used by downstream applications
  public Pair<Integer, ResultSet> executeAtomicUpdateReturnOldRow() throws SQLException {
    if (!connection.getAutoCommit()) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.AUTO_COMMIT_NOT_ENABLED).build()
        .buildException();
    }
    preExecuteUpdate();
    return executeMutation(statement, createAuditQueryLogger(statement, query),
      MutationState.ReturnResult.OLD_ROW_ALWAYS);
  }

  /**
   * Executes the given SQL statement similar to JDBC API executeUpdate() but also returns the
   * updated or non-updated row as Result object back to the client. This must be used with
   * auto-commit Connection. This makes the operation atomic. If the row is successfully updated,
   * return the updated row, otherwise if the row cannot be updated, return non-updated row.
   * @return The pair of int and ResultSet, where int represents value 1 for successful row update
   *         and 0 for non-successful row update, and ResultSet represents the state of the row.
   * @throws SQLException If the statement cannot be executed.
   */
  // Note: Do Not remove this, it is expected to be used by downstream applications
  public Pair<Integer, ResultSet> executeAtomicUpdateReturnRow() throws SQLException {
    if (!connection.getAutoCommit()) {
      throw new SQLExceptionInfo.Builder(SQLExceptionCode.AUTO_COMMIT_NOT_ENABLED).build()
        .buildException();
    }
    preExecuteUpdate();
    return executeMutation(statement, createAuditQueryLogger(statement, query),
      MutationState.ReturnResult.NEW_ROW_ON_SUCCESS);
  }

  public QueryPlan optimizeQuery() throws SQLException {
    throwIfUnboundParameters();
    return optimizeQuery(statement);
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    if (statement.getOperation().isMutation()) {
      return null;
    }
    int paramCount = statement.getBindCount();
    List<Object> params = this.getParameters();
    BitSet unsetParams = new BitSet(statement.getBindCount());
    for (int i = 0; i < paramCount; i++) {
      if (params.get(i) == BindManager.UNBOUND_PARAMETER) {
        unsetParams.set(i);
        params.set(i, null);
      }
    }
    try {
      // Just compile top level query without optimizing to get ResultSetMetaData
      QueryPlan plan = statement.compilePlan(this, Sequence.ValueOp.NOOP);
      return new PhoenixResultSetMetaData(this.getConnection(), plan.getProjector());
    } finally {
      int lastSetBit = 0;
      while ((lastSetBit = unsetParams.nextSetBit(lastSetBit)) != -1) {
        params.set(lastSetBit, BindManager.UNBOUND_PARAMETER);
        lastSetBit++;
      }
    }
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    int paramCount = statement.getBindCount();
    List<Object> params = this.getParameters();
    BitSet unsetParams = new BitSet(statement.getBindCount());
    for (int i = 0; i < paramCount; i++) {
      if (params.get(i) == BindManager.UNBOUND_PARAMETER) {
        unsetParams.set(i);
        params.set(i, null);
      }
    }
    try {
      StatementPlan plan = statement.compilePlan(this, Sequence.ValueOp.NOOP);
      return plan.getParameterMetaData();
    } finally {
      int lastSetBit = 0;
      while ((lastSetBit = unsetParams.nextSetBit(lastSetBit)) != -1) {
        params.set(lastSetBit, BindManager.UNBOUND_PARAMETER);
        lastSetBit++;
      }
    }
  }

  @Override
  public String toString() {
    return query;
  }

  @Override
  public void setArray(int parameterIndex, Array x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setBytes(int parameterIndex, byte[] x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(int parameterIndex, Blob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream, long length)
    throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setBoolean(int parameterIndex, boolean x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setByte(int parameterIndex, byte x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, int length)
    throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, long length)
    throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(int parameterIndex, Clob x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(int parameterIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setDate(int parameterIndex, Date x) throws SQLException {
    setDate(parameterIndex, x, localCalendar);
  }

  @Override
  public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
    setParameter(parameterIndex, processDate(x, cal));
  }

  private java.sql.Date processDate(Date x, Calendar cal) {
    if (x != null) { // Since Date is mutable, make a copy
      if (connection.isApplyTimeZoneDisplacement()) {
        return DateUtil.applyInputDisplacement(x, cal.getTimeZone());
      } else {
        // Since Date is mutable, make a copy
        return new Date(x.getTime());
      }
    }
    return x;
  }

  @Override
  public void setDouble(int parameterIndex, double x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setFloat(int parameterIndex, float x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setInt(int parameterIndex, int x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setLong(int parameterIndex, long x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value, long length)
    throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(int parameterIndex, NClob value) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNString(int parameterIndex, String value) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setNull(int parameterIndex, int sqlType) throws SQLException {
    setParameter(parameterIndex, null);
  }

  @Override
  public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
    setParameter(parameterIndex, null);
  }

  @Override
  public void setObject(int parameterIndex, Object o) throws SQLException {
    setParameter(parameterIndex, processObject(o));
  }

  @Override
  public void setObject(int parameterIndex, Object o, int targetSqlType) throws SQLException {
    o = processObject(o);
    PDataType targetType = PDataType.fromTypeId(targetSqlType);
    if (o != null) {
      PDataType sourceType = PDataType.fromLiteral(o);
      o = targetType.toObject(o, sourceType);
    }
    setParameter(parameterIndex, o);
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
    throws SQLException {
    setObject(parameterIndex, x, targetSqlType);
  }

  @Override
  public void setRef(int parameterIndex, Ref x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setRowId(int parameterIndex, RowId x) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setShort(int parameterIndex, short x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setString(int parameterIndex, String x) throws SQLException {
    setParameter(parameterIndex, x);
  }

  @Override
  public void setTime(int parameterIndex, Time x) throws SQLException {
    setTime(parameterIndex, x, localCalendar);
  }

  @Override
  public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
    setParameter(parameterIndex, processTime(x, cal));
  }

  private java.sql.Time processTime(Time x, Calendar cal) {
    if (x != null) {
      if (connection.isApplyTimeZoneDisplacement()) {
        return DateUtil.applyInputDisplacement(x, cal.getTimeZone());
      } else {
        // Since Time is mutable, make a copy
        return new Time(x.getTime());
      }
    }
    return x;
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
    setTimestamp(parameterIndex, x, localCalendar);
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
    setParameter(parameterIndex, processTimestamp(x, cal));
  }

  private java.sql.Timestamp processTimestamp(Timestamp x, Calendar cal) {
    if (x != null) {
      if (connection.isApplyTimeZoneDisplacement()) {
        return DateUtil.applyInputDisplacement(x, cal.getTimeZone());
      } else {
        int nanos = x.getNanos();
        x = new Timestamp(x.getTime());
        x.setNanos(nanos);
      }
    }
    return x;
  }

  @Override
  public void setURL(int parameterIndex, URL x) throws SQLException {
    setParameter(parameterIndex, x.toExternalForm()); // Just treat as String
  }

  @Override
  public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  // Convert objects to their canonical forms as expected by the Phoenix type system and apply
  // TZ displacement
  private Object processObject(Object o) {
    // We cannot use the direct conversions, as those work in the local TZ.
    if (o instanceof java.time.temporal.Temporal) {
      if (o instanceof java.time.LocalDateTime) {
        return java.sql.Timestamp.from(((java.time.LocalDateTime) o).toInstant(ZoneOffset.UTC));
      } else if (o instanceof java.time.LocalDate) {
        // java.sql.Date.from() is inherited from j.u.Date.from() and returns j.u.Date
        return new java.sql.Date(
          ((java.time.LocalDate) o).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
      } else if (o instanceof java.time.LocalTime) {
        // preserve nanos if writing to timestamp
        return java.sql.Timestamp
          .from(((java.time.LocalTime) o).atDate(DateUtil.LD_EPOCH).toInstant(ZoneOffset.UTC));
      }
    } else if (o instanceof java.util.Date) {
      if (o instanceof java.sql.Date) {
        return processDate((java.sql.Date) o, localCalendar);
      } else if (o instanceof java.sql.Timestamp) {
        return processTimestamp((java.sql.Timestamp) o, localCalendar);
      } else if (o instanceof java.sql.Time) {
        return processTime((java.sql.Time) o, localCalendar);
      } else {
        // We could use Timestamp, we don't have millis, and don't differentiate anyway
        return processDate(new java.sql.Date(((java.util.Date) o).getTime()), localCalendar)
          .getTime();
      }
    }
    return o;
  }
}
