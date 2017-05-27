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
package org.apache.beam.sdk.io.gcp.spanner;

import com.google.cloud.ByteArray;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Value;

/** Estimates the logical size of {@link com.google.cloud.spanner.Mutation}. */
class MutationSizeEstimator {

  // Prevent construction.
  private MutationSizeEstimator() {}

  /** Estimates a size of mutation in bytes. */
  static long sizeOf(Mutation m) {
    long result = 0;
    for (Value v : m.getValues()) {
      switch (v.getType().getCode()) {
        case ARRAY:
          result += estimateArrayValue(v);
          break;
        case STRUCT:
          throw new IllegalArgumentException("Structs are not supported in mutation.");
        default:
          result += estimatePrimitiveValue(v);
      }
    }
    return result;
  }

  private static long estimatePrimitiveValue(Value v) {
    switch (v.getType().getCode()) {
      case BOOL:
        return 1;
      case INT64:
      case FLOAT64:
        return 8;
      case DATE:
      case TIMESTAMP:
        return 12;
      case STRING:
        return v.isNull() ? 0 : v.getString().length();
      case BYTES:
        return v.isNull() ? 0 : v.getBytes().length();
    }
    throw new IllegalArgumentException("Unsupported type " + v.getType());
  }

  private static long estimateArrayValue(Value v) {
    switch (v.getType().getArrayElementType().getCode()) {
      case BOOL:
        return v.getBoolArray().size();
      case INT64:
        return 8 * v.getInt64Array().size();
      case FLOAT64:
        return 8 * v.getFloat64Array().size();
      case STRING:
        long totalLength = 0;
        for (String s : v.getStringArray()) {
          if (s == null) {
            continue;
          }
          totalLength += s.length();
        }
        return totalLength;
      case BYTES:
        totalLength = 0;
        for (ByteArray bytes : v.getBytesArray()) {
          if (bytes == null) {
            continue;
          }
          totalLength += bytes.length();
        }
        return totalLength;
      case DATE:
        return 12 * v.getDateArray().size();
      case TIMESTAMP:
        return 12 * v.getTimestampArray().size();
    }
    throw new IllegalArgumentException("Unsupported type " + v.getType());
  }
}
