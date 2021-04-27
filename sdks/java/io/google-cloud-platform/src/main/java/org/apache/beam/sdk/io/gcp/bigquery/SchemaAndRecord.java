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
package org.apache.beam.sdk.io.gcp.bigquery;

import com.google.api.services.bigquery.model.TableSchema;
import javax.annotation.Nullable;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.values.Row;

/**
 * A wrapper for a {@link GenericRecord} and the {@link TableSchema} representing the schema of the
 * table (or query) it was generated from.
 */
public class SchemaAndRecord {
  private final Object record;
  private final TableSchema tableSchema;

  public SchemaAndRecord(Object record, TableSchema tableSchema) {
    this.record = record;
    this.tableSchema = tableSchema;
  }

  public GenericRecord getRecord() {
    if (!(record instanceof GenericRecord)) {
      throw new IllegalStateException("Object is not GenericRecord");
    }
    return (GenericRecord) record;
  }

  public @Nullable Row getRow() {
    return record instanceof Row ? (Row) record : null;
  }

  public TableSchema getTableSchema() {
    return tableSchema;
  }
}
