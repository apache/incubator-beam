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
package org.apache.beam.sdk.schemas.io;

import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.values.Row;

/** Provider to create {@link SchemaIO}. */
public interface SchemaCapableIOProvider {
  /** Returns an id that uniquely represents this IO. */
  String identifier();

  /**
   * Returns the expected schema of the configuration object. Note this is distinct from the schema
   * of the data source itself.
   */
  Schema configurationSchema();

  /**
   * Produce a SchemaIO given a String representing the data's location, the schema of the data that
   * resides there, and some IO-specific configuration object. Can throw a {@link
   * InvalidConfigurationException} or a {@link InvalidSchemaException}.
   */
  SchemaIO from(String location, Row configuration, Schema dataSchema);
}
