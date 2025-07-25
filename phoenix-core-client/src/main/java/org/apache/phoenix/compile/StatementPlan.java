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
package org.apache.phoenix.compile;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.util.Set;
import org.apache.phoenix.jdbc.PhoenixStatement.Operation;
import org.apache.phoenix.schema.TableRef;

public interface StatementPlan {
  StatementContext getContext();

  /**
   * Returns the ParameterMetaData for the statement
   */
  ParameterMetaData getParameterMetaData();

  ExplainPlan getExplainPlan() throws SQLException;

  public Set<TableRef> getSourceRefs();

  Operation getOperation();

  /**
   * @return estimated number of rows that will be scanned when this statement plan is been
   *         executed. Returns null if the estimate cannot be provided.
   */
  public Long getEstimatedRowsToScan() throws SQLException;

  /**
   * @return estimated number of bytes that will be scanned when this statement plan is been
   *         executed. Returns null if the estimate cannot be provided.
   */
  public Long getEstimatedBytesToScan() throws SQLException;

  /**
   * @return timestamp at which the estimate information (estimated bytes and estimated rows) was
   *         computed. executed. Returns null if the information cannot be provided.
   */
  public Long getEstimateInfoTimestamp() throws SQLException;
}
