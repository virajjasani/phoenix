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
package org.apache.phoenix.schema.types;

import java.util.Date;
import org.apache.phoenix.schema.SortOrder;

public class PUnsignedDateArray extends PArrayDataType<Date> {

  public static final PUnsignedDateArray INSTANCE = new PUnsignedDateArray();

  private PUnsignedDateArray() {
    super("UNSIGNED_DATE ARRAY", PDataType.ARRAY_TYPE_BASE + PUnsignedDate.INSTANCE.getSqlType(),
      PhoenixArray.class, null, 41);
  }

  @Override
  public boolean isArrayType() {
    return true;
  }

  @Override
  public boolean isFixedWidth() {
    return false;
  }

  @Override
  public int compareTo(Object lhs, Object rhs, PDataType rhsType) {
    return compareTo(lhs, rhs);
  }

  @Override
  public Integer getByteSize() {
    return null;
  }

  @Override
  public byte[] toBytes(Object object) {
    return toBytes(object, SortOrder.ASC);
  }

  @Override
  public byte[] toBytes(Object object, SortOrder sortOrder) {
    return toBytes(object, PUnsignedDate.INSTANCE, sortOrder);
  }

  @Override
  public Object toObject(byte[] bytes, int offset, int length, PDataType actualType,
    SortOrder sortOrder, Integer maxLength, Integer scale) {
    return toObject(bytes, offset, length, PUnsignedDate.INSTANCE, sortOrder, maxLength, scale,
      PUnsignedDate.INSTANCE);
  }

  @Override
  public boolean isCoercibleTo(PDataType targetType) {
    return isCoercibleTo(targetType, this);
  }

  @Override
  public boolean isCoercibleTo(PDataType targetType, Object value) {
    if (value == null) {
      return true;
    }
    PhoenixArray pArr = (PhoenixArray) value;
    Object[] dateArr = (Object[]) pArr.array;
    for (Object i : dateArr) {
      if (!super.isCoercibleTo(PUnsignedDate.INSTANCE, i)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public Object getSampleValue(Integer maxLength, Integer arrayLength) {
    return getSampleValue(PUnsignedDate.INSTANCE, arrayLength, maxLength);
  }
}
