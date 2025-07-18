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
package org.apache.phoenix.util;

import static org.apache.phoenix.query.QueryConstants.MAX_ALLOWED_NANOS;
import static org.apache.phoenix.query.QueryConstants.MILLIS_TO_NANOS_CONVERTOR;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.Format;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.phoenix.schema.IllegalDataException;
import org.apache.phoenix.schema.TypeMismatchException;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PDataType.PDataCodec;
import org.apache.phoenix.schema.types.PDate;
import org.apache.phoenix.schema.types.PTimestamp;
import org.apache.phoenix.schema.types.PUnsignedDate;
import org.apache.phoenix.schema.types.PUnsignedTimestamp;
import org.joda.time.DateTime;
import org.joda.time.DateTimeFieldType;
import org.joda.time.DateTimeZone;
import org.joda.time.chrono.GJChronology;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.ISODateTimeFormat;

import org.apache.phoenix.thirdparty.com.google.common.collect.Lists;

@SuppressWarnings({ "serial", "deprecation" })
public class DateUtil {

  public static final String DEFAULT_TIME_ZONE_ID = "GMT";
  public static final String LOCAL_TIME_ZONE_ID = "LOCAL";
  private static final TimeZone DEFAULT_TIME_ZONE = TimeZone.getTimeZone(DEFAULT_TIME_ZONE_ID);

  public static final String DEFAULT_MS_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
  public static final Format DEFAULT_MS_DATE_FORMATTER =
    FastDateFormat.getInstance(DEFAULT_MS_DATE_FORMAT, TimeZone.getTimeZone(DEFAULT_TIME_ZONE_ID));

  public static final String DEFAULT_DATE_FORMAT = DEFAULT_MS_DATE_FORMAT;
  public static final Format DEFAULT_DATE_FORMATTER = DEFAULT_MS_DATE_FORMATTER;

  public static final String DEFAULT_TIME_FORMAT = DEFAULT_MS_DATE_FORMAT;
  public static final Format DEFAULT_TIME_FORMATTER = DEFAULT_MS_DATE_FORMATTER;

  public static final String DEFAULT_TIMESTAMP_FORMAT = DEFAULT_MS_DATE_FORMAT;
  public static final Format DEFAULT_TIMESTAMP_FORMATTER = DEFAULT_MS_DATE_FORMATTER;

  public static final java.time.LocalDate LD_EPOCH = java.time.LocalDate.of(1970, 1, 1);

  private static final DateTimeFormatter JULIAN_DATE_TIME_FORMATTER =
    new DateTimeFormatterBuilder().append(ISODateTimeFormat.dateParser())
      .appendOptional(new DateTimeFormatterBuilder().appendLiteral(' ').toParser())
      .appendOptional(
        new DateTimeFormatterBuilder().append(ISODateTimeFormat.timeParser()).toParser())
      .toFormatter().withChronology(GJChronology.getInstanceUTC());

  private DateUtil() {
  }

  @NonNull
  // FIXME why don't we just set these codecs in the Types ?
  public static PDataCodec getCodecFor(PDataType type) {
    PDataCodec codec = type.getCodec();
    if (codec != null) {
      return codec;
    }
    if (type == PTimestamp.INSTANCE) {
      return PDate.INSTANCE.getCodec();
    } else if (type == PUnsignedTimestamp.INSTANCE) {
      return PUnsignedDate.INSTANCE.getCodec();
    } else {
      throw new RuntimeException(TypeMismatchException.newException(PTimestamp.INSTANCE, type));
    }
  }

  public static TimeZone getTimeZone(String timeZoneId) {
    TimeZone parserTimeZone;
    if (timeZoneId == null || timeZoneId.equals(DateUtil.DEFAULT_TIME_ZONE_ID)) {
      parserTimeZone = DateUtil.DEFAULT_TIME_ZONE;
    } else if (LOCAL_TIME_ZONE_ID.equalsIgnoreCase(timeZoneId)) {
      parserTimeZone = TimeZone.getDefault();
    } else {
      parserTimeZone = TimeZone.getTimeZone(timeZoneId);
    }
    return parserTimeZone;
  }

  private static String[] defaultPattern;
  static {
    int maxOrdinal = Integer.MIN_VALUE;
    List<PDataType> timeDataTypes = Lists.newArrayListWithExpectedSize(6);
    for (PDataType type : PDataType.values()) {
      if (java.util.Date.class.isAssignableFrom(type.getJavaClass())) {
        timeDataTypes.add(type);
        if (type.ordinal() > maxOrdinal) {
          maxOrdinal = type.ordinal();
        }
      }
    }
    defaultPattern = new String[maxOrdinal + 1];
    for (PDataType type : timeDataTypes) {
      switch (type.getResultSetSqlType()) {
        case Types.TIMESTAMP:
          defaultPattern[type.ordinal()] = DateUtil.DEFAULT_TIMESTAMP_FORMAT;
          break;
        case Types.TIME:
          defaultPattern[type.ordinal()] = DateUtil.DEFAULT_TIME_FORMAT;
          break;
        case Types.DATE:
          defaultPattern[type.ordinal()] = DateUtil.DEFAULT_DATE_FORMAT;
          break;
      }
    }
  }

  private static String getDefaultFormat(PDataType type) {
    int ordinal = type.ordinal();
    if (ordinal >= 0 || ordinal < defaultPattern.length) {
      String format = defaultPattern[ordinal];
      if (format != null) {
        return format;
      }
    }
    throw new IllegalArgumentException("Expected a date/time type, but got " + type);
  }

  public static DateTimeParser getDateTimeParser(String pattern, PDataType pDataType,
    String timeZoneId) {
    TimeZone timeZone = getTimeZone(timeZoneId);
    String defaultPattern = getDefaultFormat(pDataType);
    if (pattern == null || pattern.length() == 0) {
      pattern = defaultPattern;
    }
    if (defaultPattern.equals(pattern)) {
      return JulianDateFormatParserFactory.getParser(timeZone);
    } else {
      return new SimpleDateFormatParser(pattern, timeZone);
    }
  }

  public static DateTimeParser getDateTimeParser(String pattern, PDataType pDataType) {
    return getDateTimeParser(pattern, pDataType, null);
  }

  public static Format getDateFormatter(String pattern) {
    return getDateFormatter(pattern, DateUtil.DEFAULT_TIME_ZONE_ID);
  }

  public static Format getDateFormatter(String pattern, String timeZoneID) {
    return DateUtil.DEFAULT_DATE_FORMAT.equals(pattern)
      && DateUtil.DEFAULT_TIME_ZONE_ID.equals(timeZoneID)
        ? DateUtil.DEFAULT_DATE_FORMATTER
        : FastDateFormat.getInstance(pattern, getTimeZone(timeZoneID));
  }

  public static Format getTimeFormatter(String pattern, String timeZoneID) {
    return DateUtil.DEFAULT_TIME_FORMAT.equals(pattern)
      && DateUtil.DEFAULT_TIME_ZONE_ID.equals(timeZoneID)
        ? DateUtil.DEFAULT_TIME_FORMATTER
        : FastDateFormat.getInstance(pattern, getTimeZone(timeZoneID));
  }

  public static Format getTimestampFormatter(String pattern, String timeZoneID) {
    return DateUtil.DEFAULT_TIMESTAMP_FORMAT.equals(pattern)
      && DateUtil.DEFAULT_TIME_ZONE_ID.equals(timeZoneID)
        ? DateUtil.DEFAULT_TIMESTAMP_FORMATTER
        : FastDateFormat.getInstance(pattern, getTimeZone(timeZoneID));
  }

  /**
   * Parses a datetime string in the UTC time zone.
   * @param dateValue datetime string in UTC
   * @return epoch ms
   */
  private static long parseDateTime(String dateTimeValue) {
    return JulianDateFormatParser.getInstance().parseDateTime(dateTimeValue);
  }

  /**
   * Parses a date string in the UTC time zone.
   * @param dateValue date string in UTC
   * @return epoch ms
   */
  public static Date parseDate(String dateValue) {
    return new Date(parseDateTime(dateValue));
  }

  /**
   * Parses a time string in the UTC time zone.
   * @param dateValue time string in UTC
   * @return epoch ms
   */
  public static Time parseTime(String timeValue) {
    return new Time(parseDateTime(timeValue));
  }

  /**
   * Parses the timestsamp string in the UTC time zone.
   * @param timestampValue timestamp string in UTC
   * @return Timestamp parsed in UTC
   */
  public static Timestamp parseTimestamp(String timestampValue) {
    Timestamp timestamp = new Timestamp(parseDateTime(timestampValue));
    int period = timestampValue.indexOf('.');
    if (period > 0) {
      String nanosStr = timestampValue.substring(period + 1);
      if (nanosStr.length() > 9) throw new IllegalDataException("nanos > 999999999 or < 0");
      if (nanosStr.length() > 3) {
        int nanos = Integer.parseInt(nanosStr);
        for (int i = 0; i < 9 - nanosStr.length(); i++) {
          nanos *= 10;
        }
        timestamp.setNanos(nanos);
      }
    }
    return timestamp;
  }

  /**
   * Utility function to work around the weirdness of the {@link Timestamp} constructor. This method
   * takes the milli-seconds that spills over to the nanos part as part of constructing the
   * {@link Timestamp} object. If we just set the nanos part of timestamp to the nanos passed in
   * param, we end up losing the sub-second part of timestamp.
   */
  public static Timestamp getTimestamp(long millis, int nanos) {
    if (nanos > MAX_ALLOWED_NANOS || nanos < 0) {
      throw new IllegalArgumentException("nanos > " + MAX_ALLOWED_NANOS + " or < 0");
    }
    Timestamp ts = new Timestamp(millis);
    if (ts.getNanos() + nanos > MAX_ALLOWED_NANOS) {
      int millisToNanosConvertor = BigDecimal.valueOf(MILLIS_TO_NANOS_CONVERTOR).intValue();
      int overFlowMs = (ts.getNanos() + nanos) / millisToNanosConvertor;
      int overFlowNanos = (ts.getNanos() + nanos) - (overFlowMs * millisToNanosConvertor);
      ts = new Timestamp(millis + overFlowMs);
      ts.setNanos(ts.getNanos() + overFlowNanos);
    } else {
      ts.setNanos(ts.getNanos() + nanos);
    }
    return ts;
  }

  /**
   * Utility function to convert a {@link BigDecimal} value to {@link Timestamp}.
   */
  public static Timestamp getTimestamp(BigDecimal bd) {
    return DateUtil.getTimestamp(bd.longValue(),
      ((bd.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(MILLIS_TO_NANOS_CONVERTOR)))
        .intValue()));
  }

  public static interface DateTimeParser {
    public long parseDateTime(String dateTimeString) throws IllegalDataException;

    public TimeZone getTimeZone();
  }

  /**
   * This class is used when a user explicitly provides phoenix.query.dateFormat in configuration
   */
  private static class SimpleDateFormatParser implements DateTimeParser {
    private String datePattern;
    private SimpleDateFormat parser;

    public SimpleDateFormatParser(String pattern, TimeZone timeZone) {
      datePattern = pattern;
      parser = new SimpleDateFormat(pattern) {
        @Override
        public java.util.Date parseObject(String source) throws ParseException {
          java.util.Date date = super.parse(source);
          return new java.sql.Date(date.getTime());
        }
      };
      parser.setTimeZone(timeZone);
    }

    @Override
    public long parseDateTime(String dateTimeString) throws IllegalDataException {
      try {
        java.util.Date date = parser.parse(dateTimeString);
        return date.getTime();
      } catch (ParseException e) {
        throw new IllegalDataException("Unable to parse date/time '" + dateTimeString
          + "' using format string of '" + datePattern + "'.");
      }
    }

    @Override
    public TimeZone getTimeZone() {
      return parser.getTimeZone();
    }
  }

  private static class JulianDateFormatParserFactory {
    private JulianDateFormatParserFactory() {
    }

    public static DateTimeParser getParser(final TimeZone timeZone) {
      // If timeZone matches default, get singleton DateTimeParser
      if (timeZone.equals(DEFAULT_TIME_ZONE)) {
        return JulianDateFormatParser.getInstance();
      }
      // Otherwise, create new DateTimeParser
      return new DateTimeParser() {
        private final DateTimeFormatter formatter =
          JULIAN_DATE_TIME_FORMATTER.withZone(DateTimeZone.forTimeZone(timeZone));

        @Override
        public long parseDateTime(String dateTimeString) throws IllegalDataException {
          try {
            return formatter.parseDateTime(dateTimeString).getMillis();
          } catch (IllegalArgumentException ex) {
            throw new IllegalDataException(ex);
          }
        }

        @Override
        public TimeZone getTimeZone() {
          return timeZone;
        }
      };
    }
  }

  /**
   * This class is our default DateTime string parser
   */
  private static class JulianDateFormatParser implements DateTimeParser {
    private static final JulianDateFormatParser INSTANCE = new JulianDateFormatParser();

    public static JulianDateFormatParser getInstance() {
      return INSTANCE;
    }

    private final DateTimeFormatter formatter =
      JULIAN_DATE_TIME_FORMATTER.withZone(DateTimeZone.UTC);

    private JulianDateFormatParser() {
    }

    @Override
    public long parseDateTime(String dateTimeString) throws IllegalDataException {
      try {
        return formatter.parseDateTime(dateTimeString).getMillis();
      } catch (IllegalArgumentException ex) {
        throw new IllegalDataException(ex);
      }
    }

    @Override
    public TimeZone getTimeZone() {
      return formatter.getZone().toTimeZone();
    }
  }

  public static long rangeJodaHalfEven(DateTime roundedDT, DateTime otherDT,
    DateTimeFieldType type) {
    // It's OK if this is slow, as it's only called O(1) times per query
    //
    // We need to reverse engineer what roundHalfEvenCopy() does
    // and return the lower/upper (inclusive) range here
    // Joda simply works on milliseconds between the floor and ceil values.
    // We could avoid the period call for units less than a day, but this is not a perf
    // critical function.
    long roundedMs = roundedDT.getMillis();
    long otherMs = otherDT.getMillis();
    long midMs = (roundedMs + otherMs) / 2;
    long remainder = (roundedMs + otherMs) % 2;
    if (remainder == 0) {
      int roundedUnits = roundedDT.get(type);
      if (otherMs > roundedMs) {
        // Upper range, other is bigger.
        if ((roundedUnits & 1) == 0) {
          // This unit is even, the next second is odd, so we get the mid point
          return midMs;
        } else {
          // This unit is odd, the next second is even and takes the midpoint.
          return midMs - 1;
        }
      } else {
        // Lower range, other is smaller.
        if ((roundedUnits & 1) == 0) {
          // This unit is even, the next second is odd, so we get the mid point
          return midMs;
        } else {
          // This unit is odd, the next second is even and takes the midpoint.
          return midMs + 1;
        }
      }
    } else {
      // probably never happens
      if (otherMs > roundedMs) {
        // Upper range, return the rounded down value
        return midMs;
      } else {
        // Lower range, the mid value belongs to the previous unit.
        return midMs + 1;
      }
    }
  }

  // These implementations favour speed over historical correctness, and use
  // java.util.TimeZone#getOffset(epoch millis) and inherit its limitations.

  // When we switch to java.time, we might want to revisit this, and add an option for
  // slower but more correct conversions.
  // However, any conversion for TZs with DST is best effort anyway.

  /**
   * Apply the time zone displacement to the input, so that the output represents the same
   * LocalDateTime in the UTC time zone as the Input in the specified time zone.
   * @param jdbc     Date interpreted in timeZone
   * @param timeZone for displacement calculation
   * @return input with the TZ displacement applied
   */
  public static java.sql.Date applyInputDisplacement(java.sql.Date jdbc, TimeZone timeZone) {
    long epoch = jdbc.getTime();
    return new java.sql.Date(epoch + timeZone.getOffset(epoch));
  }

  /**
   * Apply the time zone displacement to the input, so that the output represents the same
   * LocalDateTime in the UTC time zone as the Input in the specified time zone.
   * @param jdbc     Time interpreted in timeZone
   * @param timeZone for displacement calculation
   * @return input with the TZ displacement applied
   */
  public static java.sql.Time applyInputDisplacement(java.sql.Time jdbc, TimeZone timeZone) {
    long epoch = jdbc.getTime();
    return new java.sql.Time(epoch + timeZone.getOffset(epoch));
  }

  /**
   * Apply the time zone displacement to the input, so that the output represents the same
   * LocalDateTime in the UTC time zone as the Input in the specified time zone.
   * @param jdbc     Timestamp interpreted in timeZone
   * @param timeZone for displacement calculation
   * @return input with the TZ displacement applied
   */
  public static java.sql.Timestamp applyInputDisplacement(java.sql.Timestamp jdbc,
    TimeZone timeZone) {
    long epoch = jdbc.getTime();
    java.sql.Timestamp ts = new java.sql.Timestamp(epoch + timeZone.getOffset(epoch));
    ts.setNanos(jdbc.getNanos());
    return ts;
  }

  /**
   * Apply the time zone displacement to the input, so that the output represents the same
   * LocalDateTime in the specified time zone as the Input in the UTC time zone.
   * @param internal Date as UTC epoch
   * @param timeZone for displacement calculation
   * @return input with the TZ displacement applied
   */
  public static java.sql.Date applyOutputDisplacement(java.sql.Date internal, TimeZone timeZone) {
    long epoch = internal.getTime();
    return new java.sql.Date(epoch - getReverseOffset(epoch, timeZone));
  }

  /**
   * Apply the time zone displacement to the input, so that the output represents the same
   * LocalDateTime in the specified time zone as the Input in the UTC time zone.
   * @param internal Date as UTC epoch
   * @param timeZone for displacement calculation
   * @return input with the TZ displacement applied
   */
  public static java.sql.Time applyOutputDisplacement(java.sql.Time internal, TimeZone timeZone) {
    long epoch = internal.getTime();
    return new java.sql.Time(epoch - getReverseOffset(epoch, timeZone));
  }

  /**
   * Apply the time zone displacement to the input, so that the output represents the same
   * LocalDateTime in the specified time zone as the Input in the UTC time zone.
   * @param internal Timestamp as UTC epoch
   * @param timeZone for displacement calculation
   * @return input with the TZ displacement applied
   */
  public static java.sql.Timestamp applyOutputDisplacement(java.sql.Timestamp internal,
    TimeZone timeZone) {
    long epoch = internal.getTime();
    java.sql.Timestamp ts = new java.sql.Timestamp(epoch - getReverseOffset(epoch, timeZone));
    ts.setNanos(internal.getNanos());
    return ts;
  }

  private static int getReverseOffset(long epoch, TimeZone tz) {
    return tz.getOffset(epoch - tz.getRawOffset() - tz.getDSTSavings());
  }

}
