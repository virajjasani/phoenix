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
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.util.DateUtil;
import org.joda.time.DateTime;
import org.joda.time.DateTimeFieldType;
import org.joda.time.chrono.GJChronology;

/**
 * Rounds off the given {@link DateTime} to year.
 */
public class RoundYearExpression extends RoundJodaDateExpression {

  public RoundYearExpression() {
  }

  public RoundYearExpression(List<Expression> children) {
    super(children);
  }

  @Override
  public long roundDateTime(DateTime dateTime) {
    return dateTime.year().roundHalfEvenCopy().getMillis();
  }

  @Override
  public long rangeLower(long epochMs) {
    // We're doing unnecessary conversions here, but this is NOT perf sensitive
    DateTime rounded =
      new DateTime(roundDateTime(new DateTime(epochMs, GJChronology.getInstanceUTC())),
        GJChronology.getInstanceUTC());
    DateTime prev = rounded.minusYears(1);
    return DateUtil.rangeJodaHalfEven(rounded, prev, DateTimeFieldType.year());
  }

  @Override
  public long rangeUpper(long epochMs) {
    DateTime rounded =
      new DateTime(roundDateTime(new DateTime(epochMs, GJChronology.getInstanceUTC())),
        GJChronology.getInstanceUTC());
    DateTime next = rounded.plusYears(1);
    return DateUtil.rangeJodaHalfEven(rounded, next, DateTimeFieldType.year());
  }
}
