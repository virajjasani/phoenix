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

import java.util.Arrays;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.exception.UndecodableByteException;
import org.apache.phoenix.schema.SortOrder;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

public class StringUtil {
  public static final String EMPTY_STRING = "";
  // Masks to determine how many bytes are in each character
  // From http://tools.ietf.org/html/rfc3629#section-3
  public static final byte SPACE_UTF8 = 0x20;
  private static final int BYTES_1_MASK = 0xFF << 7; // 0xxxxxxx is a single byte char
  private static final int BYTES_2_MASK = 0xFF << 5; // 110xxxxx is a double byte char
  private static final int BYTES_3_MASK = 0xFF << 4; // 1110xxxx is a triple byte char
  private static final int BYTES_4_MASK = 0xFF << 3; // 11110xxx is a quadruple byte char

  public static final byte INVERTED_SPACE_UTF8 =
    SortOrder.invert(new byte[] { SPACE_UTF8 }, 0, new byte[1], 0, 1)[0];
  public final static char SINGLE_CHAR_WILDCARD = '?';
  public final static char SINGLE_CHAR_LIKE = '_';
  public final static char MULTI_CHAR_WILDCARD = '*';
  public final static char MULTI_CHAR_LIKE = '%';
  private final static String[] LIKE_ESCAPE_SEQS =
    new String[] { "\\" + SINGLE_CHAR_LIKE, "\\" + MULTI_CHAR_LIKE };
  private final static String[] LIKE_UNESCAPED_SEQS =
    new String[] { "" + SINGLE_CHAR_LIKE, "" + MULTI_CHAR_LIKE };

  private StringUtil() {
  }

  public static final String[] getLikeEscapeSeqs() {
    return Arrays.copyOf(LIKE_ESCAPE_SEQS, LIKE_ESCAPE_SEQS.length);
  }

  public static final String[] getLikeUnescapedSeqs() {
    return Arrays.copyOf(LIKE_UNESCAPED_SEQS, LIKE_UNESCAPED_SEQS.length);
  }

  /** Replace instances of character ch in String value with String replacement */
  public static String replaceChar(String value, char ch, CharSequence replacement) {
    if (value == null) return null;
    int i = value.indexOf(ch);
    if (i == -1) return value; // nothing to do

    // we've got at least one character to replace
    StringBuilder buf = new StringBuilder(value.length() + 16); // some extra space
    int j = 0;
    while (i != -1) {
      buf.append(value, j, i).append(replacement);
      j = i + 1;
      i = value.indexOf(ch, j);
    }
    if (j < value.length()) buf.append(value, j, value.length());
    return buf.toString();
  }

  /**
   * @return the replacement of all occurrences of src[i] with target[i] in s. Src and target are
   *         not regex's so this uses simple searching with indexOf()
   */
  public static String replace(String s, String[] src, String[] target) {
    assert src != null && target != null && src.length > 0 && src.length == target.length;
    if (src.length == 1 && src[0].length() == 1) {
      return replaceChar(s, src[0].charAt(0), target[0]);
    }
    if (s == null) return null;
    StringBuilder sb = new StringBuilder(s.length());
    int pos = 0;
    int limit = s.length();
    int lastMatch = 0;
    while (pos < limit) {
      boolean matched = false;
      for (int i = 0; i < src.length; i++) {
        if (s.startsWith(src[i], pos) && src[i].length() > 0) {
          // we found a matching pattern - append the acculumation plus the replacement
          sb.append(s.substring(lastMatch, pos)).append(target[i]);
          pos += src[i].length();
          lastMatch = pos;
          matched = true;
          break;
        }
      }
      if (!matched) {
        // we didn't match any patterns, so move forward 1 character
        pos++;
      }
    }
    // see if we found any matches
    if (lastMatch == 0) {
      // we didn't match anything, so return the source string
      return s;
    }

    // apppend the trailing portion
    sb.append(s.substring(lastMatch));

    return sb.toString();
  }

  public static int getBytesInChar(byte b, SortOrder sortOrder) {
    int ret = getBytesInCharNoException(b, sortOrder);
    if (ret == -1) throw new UndecodableByteException(b);
    return ret;
  }

  private static int getBytesInCharNoException(byte b, SortOrder sortOrder) {
    Preconditions.checkNotNull(sortOrder);
    if (sortOrder == SortOrder.DESC) {
      b = SortOrder.invert(b);
    }
    int c = b & 0xff;
    if ((c & BYTES_1_MASK) == 0) return 1;
    if ((c & BYTES_2_MASK) == 0xC0) return 2;
    if ((c & BYTES_3_MASK) == 0xE0) return 3;
    if ((c & BYTES_4_MASK) == 0xF0) return 4;
    return -1;
  }

  public static int calculateUTF8Length(byte[] bytes, int offset, int length, SortOrder sortOrder) {
    int i = offset, endOffset = offset + length;
    length = 0;
    while (i < endOffset) {
      int charLength = getBytesInChar(bytes[i], sortOrder);
      i += charLength;
      length++;
    }
    return length;
  }

  // given an array of bytes containing utf-8 encoded strings, starting from curPos, ending before
  // range, and return the next character offset, -1 if no next character available or
  // UndecodableByteException
  private static int calculateNextCharOffset(byte[] bytes, int curPos, int range,
    SortOrder sortOrder) {
    int ret = getBytesInCharNoException(bytes[curPos], sortOrder);
    if (ret == -1) return -1;
    ret += curPos;
    if (ret >= range) return -1;
    return ret;
  }

  // given an array of bytes containing utf-8 encoded strings, starting from offset, and return
  // the previous character offset , -1 if UndecodableByteException. curPos points to current
  // character starting offset.
  private static int calculatePreCharOffset(byte[] bytes, int curPos, int offset,
    SortOrder sortOrder) {
    --curPos;
    for (int i = 1, pos = curPos - i + 1; i <= 4 && offset <= pos; ++i, --pos) {
      int ret = getBytesInCharNoException(bytes[pos], sortOrder);
      if (ret == i) return pos;
    }
    return -1;
  }

  // return actural offsetInBytes corresponding to offsetInStr in utf-8 encoded strings bytes
  // containing
  // @param bytes an array of bytes containing utf-8 encoded strings
  // @param offset
  // @param length
  // @param sortOrder
  // @param offsetInStr offset for utf-8 encoded strings bytes array containing. Can be negative
  // meaning counting from the end of encoded strings
  // @return actural offsetInBytes corresponding to offsetInStr. -1 if offsetInStr is out of index
  public static int calculateUTF8Offset(byte[] bytes, int offset, int length, SortOrder sortOrder,
    int offsetInStr) {
    if (offsetInStr == 0) return offset;
    int ret, range = offset + length;
    if (offsetInStr > 0) {
      ret = offset;
      while (offsetInStr > 0) {
        ret = calculateNextCharOffset(bytes, ret, range, sortOrder);
        if (ret == -1) return -1;
        --offsetInStr;
      }
    } else {
      ret = offset + length;
      while (offsetInStr < 0) {
        ret = calculatePreCharOffset(bytes, ret, offset, sortOrder);
        // if calculateCurCharOffset returns -1, ret must be smaller than offset
        if (ret < offset) return -1;
        ++offsetInStr;
      }
    }
    return ret;
  }

  // Given an array of bytes containing encoding utf-8 encoded strings, the offset and a length
  // parameter, return the actual index into the byte array which would represent a substring
  // of <length> starting from the character at <offset>. We assume the <offset> is the start
  // byte of an UTF-8 character.
  public static int getByteLengthForUtf8SubStr(byte[] bytes, int offset, int length,
    SortOrder sortOrder) {
    int byteLength = 0;
    while (length > 0 && offset + byteLength < bytes.length) {
      int charLength = getBytesInChar(bytes[offset + byteLength], sortOrder);
      byteLength += charLength;
      length--;
    }
    return byteLength;
  }

  public static boolean hasMultiByteChars(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c > 0x007F) {
        return true;
      }
    }
    return false;
  }

  public static int getFirstNonBlankCharIdxFromStart(byte[] string, int offset, int length,
    SortOrder sortOrder) {
    int i = offset;
    byte space = sortOrder == SortOrder.ASC ? SPACE_UTF8 : INVERTED_SPACE_UTF8;
    for (; i < offset + length; i++) {
      if (string[i] != space) {
        break;
      }
    }
    return i;
  }

  public static int getFirstNonBlankCharIdxFromEnd(byte[] string, int offset, int length,
    SortOrder sortOrder) {
    int i = offset + length - 1;
    byte space = sortOrder == SortOrder.ASC ? SPACE_UTF8 : INVERTED_SPACE_UTF8;
    for (; i >= offset; i--) {
      if (string[i] != space) {
        break;
      }
    }
    return i;
  }

  // A toBytes function backed up HBase's utility function, but would accept null input, in which
  // case it returns an empty byte array.
  public static byte[] toBytes(String input) {
    if (input == null) {
      return ByteUtil.EMPTY_BYTE_ARRAY;
    }
    return Bytes.toBytes(input);
  }

  public static String escapeLike(String s) {
    return replace(s, LIKE_UNESCAPED_SEQS, LIKE_ESCAPE_SEQS);
  }

  public static int getUnpaddedCharLength(byte[] b, int offset, int length, SortOrder sortOrder) {
    return getFirstNonBlankCharIdxFromEnd(b, offset, length, sortOrder) - offset + 1;
  }

  public static byte[] padChar(byte[] value, Integer byteSize) {
    byte[] newValue = Arrays.copyOf(value, byteSize);
    if (newValue.length > value.length) {
      Arrays.fill(newValue, value.length, newValue.length, SPACE_UTF8);
    }
    return newValue;
  }

  /**
   * Lame - StringBuilder.equals is retarded.
   * @return whether or not the two builders consist the same sequence of characters
   */
  public static boolean equals(StringBuilder b1, StringBuilder b2) {
    if (b1.length() != b2.length()) {
      return false;
    }
    for (int i = 0; i < b1.length(); i++) {
      if (b1.charAt(i) != b2.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * LPAD implementation array containing string to be left padded byte offset of string byte length
   * of string array containing fill values byte offset of fill byte length of fill if true inverts
   * the bits in fill before filling the array length of the string that is returned with fill
   * values left padded
   * @return byte[] containing left padded string
   */
  public static byte[] lpad(byte[] str, int strOffset, int strLength, byte[] fill, int fillOffset,
    int fillLength, boolean invertFill, int strWithPaddingLen) {
    byte[] paddedStr = new byte[strWithPaddingLen];
    int fillStopIdx = strWithPaddingLen - strLength;
    // copy fill into the start of paddedStr
    fill(paddedStr, 0, fillStopIdx, fill, fillOffset, fillOffset + fillLength, invertFill);
    // fill remaining characters with original string
    System.arraycopy(str, strOffset, paddedStr, fillStopIdx, strLength);
    return paddedStr;
  }

  /**
   * Assigns the specified byte values to elements of the specified range of the specified array of
   * bytes. The range to be filled extends from index fromIndex, inclusive, to index toIndex,
   * exclusive. (If fromIndex==toIndex, the range to be filled is empty.) the array to be filled the
   * index of the first element (inclusive) to be filled with the fill values the index of the last
   * element (exclusive) to be filled with the fill values the values to be stored in all elements
   * of the array the index of the first element (inclusive) to be used as fill values the index of
   * the last element (exclusive) to be used as fill value if true inverts the bits in fill before
   * filling the array
   */
  public static void fill(byte[] str, int strFromIdx, int strToIdx, byte[] fillArray,
    int fillFromIdx, int fillToIdx, boolean invertFill) {
    rangeCheck(str.length, strFromIdx, strToIdx);
    rangeCheck(fillArray.length, fillFromIdx, fillToIdx);
    int strIdx = strFromIdx;
    byte[] fill = fillArray;
    int fillLen = fillToIdx - fillFromIdx;
    if (invertFill) fill = SortOrder.invert(fillArray, fillFromIdx, fillLen);
    while (strIdx < strToIdx) {
      int fillIdx = fillFromIdx;
      while (fillIdx < fillToIdx && strIdx < strToIdx) {
        if (strIdx + fillLen < fillToIdx) {
          System.arraycopy(fill, fillFromIdx, str, strIdx, fillLen);
        } else {
          str[strIdx++] = fill[fillIdx++];
        }
      }
    }
  }

  /**
   * Checks that fromIndex and toIndex are in the range and throws an appropriate exception, if they
   * are not
   */
  private static void rangeCheck(int length, int fromIndex, int toIndex) {
    if (fromIndex > toIndex) {
      throw new IllegalArgumentException("fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
    }
    if (fromIndex < 0) {
      throw new ArrayIndexOutOfBoundsException(fromIndex);
    }
    if (toIndex > length) {
      throw new ArrayIndexOutOfBoundsException(toIndex);
    }
  }

  public static String escapeStringConstant(String pattern) {
    // commons-lang3 dropped StringEscapeUtils.escapeSql because it was
    // extremely naive in its implementation. Copying that implementation
    // here as a stop-gap.
    // https://stackoverflow.com/questions/32096614/migrating-stringescapeutils-escapesql-from-commons-lang
    if (pattern == null) {
      return null;
    }
    return org.apache.commons.lang3.StringUtils.replace(pattern, "'", "''");
  }

  public static String escapeBackslash(String input) {
    // see
    // http://stackoverflow.com/questions/4653831/regex-how-to-escape-backslashes-and-special-characters
    return input.replaceAll("\\\\", "\\\\\\\\");
  }
}
