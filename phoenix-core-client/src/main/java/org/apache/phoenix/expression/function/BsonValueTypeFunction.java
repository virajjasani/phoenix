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
package org.apache.phoenix.expression.function;

import java.util.List;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.util.bson.CommonComparisonExpressionUtils;
import org.apache.phoenix.parse.BsonValueTypeParseNode;
import org.apache.phoenix.parse.FunctionParseNode;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PBoolean;
import org.apache.phoenix.schema.types.PBson;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PDate;
import org.apache.phoenix.schema.types.PDouble;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.schema.types.PJson;
import org.apache.phoenix.schema.types.PLong;
import org.apache.phoenix.schema.types.PVarbinary;
import org.apache.phoenix.schema.types.PVarchar;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * BSON_VALUE_TYPE function to retrieve the SQL data type name of any field in BSON. This can be
 * used for any top-level or nested Bson fields. 1. The first argument represents BSON Object on
 * which the function performs scan. 2. The second argument represents the field key. The field key
 * can represent any top level or nested fields within the document. The caller should use "."
 * notation for accessing nested document elements and "[n]" notation for accessing nested array
 * elements. Top level fields do not require any additional character.
 */
@FunctionParseNode.BuiltInFunction(name = BsonValueTypeFunction.NAME,
    nodeClass = BsonValueTypeParseNode.class,
    args = {
      @FunctionParseNode.Argument(allowedTypes = { PJson.class, PBson.class, PVarbinary.class }),
      @FunctionParseNode.Argument(allowedTypes = { PVarchar.class }, isConstant = true), })
public class BsonValueTypeFunction extends ScalarFunction {

  public static final String NAME = "BSON_VALUE_TYPE";

  public BsonValueTypeFunction() {
    // no-op
  }

  public BsonValueTypeFunction(List<Expression> children) {
    super(children);
    Preconditions.checkNotNull(getChildren().get(1));
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
    if (!getChildren().get(0).evaluate(tuple, ptr)) {
      return false;
    }
    if (ptr == null || ptr.getLength() == 0) {
      return false;
    }

    Object object = PBson.INSTANCE.toObject(ptr, getChildren().get(0).getSortOrder());
    RawBsonDocument rawBsonDocument = (RawBsonDocument) object;

    if (!getChildren().get(1).evaluate(tuple, ptr)) {
      return false;
    }
    if (ptr.getLength() == 0) {
      return false;
    }

    String documentFieldKey =
      (String) PVarchar.INSTANCE.toObject(ptr, getChildren().get(1).getSortOrder());
    if (documentFieldKey == null) {
      return false;
    }

    BsonValue bsonValue =
      CommonComparisonExpressionUtils.getFieldFromDocument(documentFieldKey, rawBsonDocument);
    if (bsonValue == null) {
      ptr.set(PVarchar.INSTANCE.toBytes("NULL"));
      return true;
    }

    String sqlTypeName = getValueType(bsonValue);
    ptr.set(PVarchar.INSTANCE.toBytes(sqlTypeName));
    return true;
  }

  private String getValueType(BsonValue bsonValue) {
    if (bsonValue instanceof BsonString) {
      return PVarchar.INSTANCE.getSqlTypeName();
    } else if (bsonValue instanceof BsonInt32) {
      return PInteger.INSTANCE.getSqlTypeName();
    } else if (bsonValue instanceof BsonInt64) {
      return PLong.INSTANCE.getSqlTypeName();
    } else if (bsonValue instanceof BsonDouble || bsonValue instanceof BsonDecimal128) {
      return PDouble.INSTANCE.getSqlTypeName();
    } else if (bsonValue instanceof BsonBoolean) {
      return PBoolean.INSTANCE.getSqlTypeName();
    } else if (bsonValue instanceof BsonBinary) {
      return PVarbinary.INSTANCE.getSqlTypeName();
    } else if (bsonValue instanceof BsonDateTime) {
      return PDate.INSTANCE.getSqlTypeName();
    } else if (bsonValue instanceof BsonDocument || bsonValue instanceof BsonArray) {
      return PBson.INSTANCE.getSqlTypeName();
    } else if (bsonValue instanceof BsonNull) {
      return "NULL";
    } else {
      return PVarchar.INSTANCE.getSqlTypeName();
    }
  }

  @Override
  public PDataType<?> getDataType() {
    return PVarchar.INSTANCE;
  }
}
