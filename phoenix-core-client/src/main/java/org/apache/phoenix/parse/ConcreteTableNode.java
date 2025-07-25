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
package org.apache.phoenix.parse;

import org.apache.phoenix.util.SchemaUtil;

/**
 * Abstract node representing a table reference in the FROM clause in SQL
 * @since 0.1
 */
public abstract class ConcreteTableNode extends TableNode {
  // DEFAULT_TABLE_SAMPLING_RATE alternative is to set as 100d
  public static final Double DEFAULT_TABLE_SAMPLING_RATE = null;
  private final TableName name;
  private final Double tableSamplingRate;

  ConcreteTableNode(String alias, TableName name) {
    this(alias, name, DEFAULT_TABLE_SAMPLING_RATE);
  }

  ConcreteTableNode(String alias, TableName name, Double tableSamplingRate) {
    super(SchemaUtil.normalizeIdentifier(alias));
    this.name = name;
    if (tableSamplingRate == null) {
      this.tableSamplingRate = DEFAULT_TABLE_SAMPLING_RATE;
    } else if (tableSamplingRate < 0d || tableSamplingRate > 100d) {
      throw new IllegalArgumentException("TableSamplingRate is out of bound of 0 and 100");
    } else {
      this.tableSamplingRate = tableSamplingRate;
    }
  }

  public TableName getName() {
    return name;
  }

  public Double getTableSamplingRate() {
    return tableSamplingRate;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((name == null) ? 0 : name.hashCode());
    result = prime * result + ((tableSamplingRate == null) ? 0 : tableSamplingRate.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    ConcreteTableNode other = (ConcreteTableNode) obj;
    if (name == null) {
      if (other.name != null) return false;
    } else if (!name.equals(other.name)) return false;
    if (tableSamplingRate == null) {
      if (other.tableSamplingRate != null) return false;
    } else if (!tableSamplingRate.equals(other.tableSamplingRate)) return false;
    return true;
  }
}
