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
package org.apache.phoenix.schema.tuple;

import java.io.IOException;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValue.Type;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.hbase.index.ValueGetter;
import org.apache.phoenix.hbase.index.covered.update.ColumnReference;

/**
 * Class used to construct a {@link Tuple} in order to evaluate an {@link Expression}
 */
public class ValueGetterTuple extends BaseTuple {
  private final ValueGetter valueGetter;
  private final long ts;

  public ValueGetterTuple(ValueGetter valueGetter, long ts) {
    this.valueGetter = valueGetter;
    this.ts = ts;
  }

  public ValueGetterTuple() {
    this.valueGetter = null;
    this.ts = HConstants.LATEST_TIMESTAMP;
  }

  @Override
  public void getKey(ImmutableBytesWritable ptr) {
    ptr.set(valueGetter.getRowKey());
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  public KeyValue getValueUnsafe(byte[] family, byte[] qualifier) {
    try {
      return valueGetter.getLatestKeyValue(new ColumnReference(family, qualifier), ts);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public KeyValue getValue(byte[] family, byte[] qualifier) {
    KeyValue kv = getValueUnsafe(family, qualifier);
    if (kv != null) {
      return kv;
    }
    byte[] rowKey = valueGetter.getRowKey();
    byte[] valueBytes = HConstants.EMPTY_BYTE_ARRAY;
    return new KeyValue(rowKey, 0, rowKey.length, family, 0, family.length, qualifier, 0,
      qualifier.length, ts, Type.Put, valueBytes, 0, 0);
  }

  @Override
  public String toString() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int size() {
    throw new UnsupportedOperationException();
  }

  @Override
  public KeyValue getValue(int index) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean getValue(byte[] family, byte[] qualifier, ImmutableBytesWritable ptr) {
    KeyValue kv = getValue(family, qualifier);
    if (kv == null) {
      return false;
    }
    ptr.set(kv.getValueArray(), kv.getValueOffset(), kv.getValueLength());
    return true;
  }

  public boolean getValueUnsafe(byte[] family, byte[] qualifier, ImmutableBytesWritable ptr) {
    KeyValue kv = getValueUnsafe(family, qualifier);
    if (kv == null) {
      return false;
    }
    ptr.set(kv.getValueArray(), kv.getValueOffset(), kv.getValueLength());
    return true;
  }

  @Override
  public long getSerializedSize() {
    if (valueGetter == null) {
      return 0;
    }
    // Since the values are retrieved on demand,
    // we only count the rowKey size as that's what we have in memory.
    byte[] rowKey = valueGetter.getRowKey();
    return rowKey != null ? rowKey.length : 0;
  }

}
