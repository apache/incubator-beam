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
package org.apache.beam.sdk.extensions.sql;

import java.util.List;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.junit.Test;

/**
 * A simple Analytic Functions experiment for BeamSQL created in order to understand the query
 * processing workflow of BeamSQL and Calcite.
 */
public class BeamAnalyticFunctionsExperimentTest extends BeamSqlDslBase {

  /**
   * Table schema and data taken from.
   * https://cloud.google.com/bigquery/docs/reference/standard-sql/analytic-function-concepts#produce_table
   */
  private PCollection<Row> inputData() {
    Schema schema =
        Schema.builder()
            .addStringField("item")
            .addStringField("category")
            .addInt32Field("purchases")
            .build();
    return pipeline
        .apply(
            Create.of(
                TestUtils.rowsBuilderOf(schema)
                    .addRows(
                        "kale",
                        "vegetable",
                        23,
                        "orange",
                        "fruit",
                        2,
                        "cabbage",
                        "vegetable",
                        9,
                        "apple",
                        "fruit",
                        8,
                        "leek",
                        "vegetable",
                        2,
                        "lettuce",
                        "vegetable",
                        10)
                    .getRows()))
        .setRowSchema(schema);
  }

  /**
   * Compute a cumulative sum query taken from.
   * https://cloud.google.com/bigquery/docs/reference/standard-sql/analytic-function-concepts#compute_a_cumulative_sum
   */
  @Test
  public void testOverCumulativeSum() throws Exception {
    pipeline.enableAbandonedNodeEnforcement(false);
    PCollection<Row> inputRows = inputData();
    String sql =
        "SELECT item, purchases, category, sum(purchases) over "
            + "("
            + "PARTITION BY category "
            + "ORDER BY purchases "
            + "ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"
            + ")"
            + " as total_purchases  FROM PCOLLECTION";
    PCollection<Row> result = inputRows.apply("sql", SqlTransform.query(sql));

    Schema overResultSchema =
        Schema.builder()
            .addStringField("item")
            .addInt32Field("purchases")
            .addStringField("category")
            .addInt32Field("total_purchases")
            .build();

    List<Row> overResult =
        TestUtils.RowsBuilder.of(overResultSchema)
            .addRows(
                "orange",
                2,
                "fruit",
                2,
                "apple",
                8,
                "fruit",
                10,
                "leek",
                2,
                "vegetable",
                2,
                "cabbage",
                9,
                "vegetable",
                11,
                "lettuce",
                10,
                "vegetable",
                21,
                "kale",
                23,
                "vegetable",
                44)
            .getRows();

    PAssert.that(result).containsInAnyOrder(overResult);

    pipeline.run();
  }

  /**
   * Basic analytic function query taken from.
   * https://cloud.google.com/bigquery/docs/reference/standard-sql/analytic-function-concepts#compute_a_grand_total
   */
  @Test
  public void testSimpleOverFunction() throws Exception {
    pipeline.enableAbandonedNodeEnforcement(false);
    PCollection<Row> inputRows = inputData();
    String sql =
        "SELECT item, purchases, category, sum(purchases) over ()"
            + " as total_purchases  FROM PCOLLECTION";
    PCollection<Row> result = inputRows.apply("sql", SqlTransform.query(sql));

    Schema overResultSchema =
        Schema.builder()
            .addStringField("item")
            .addInt32Field("purchases")
            .addStringField("category")
            .addInt32Field("total_purchases")
            .build();

    List<Row> overResult =
        TestUtils.RowsBuilder.of(overResultSchema)
            .addRows(
                "orange",
                2,
                "fruit",
                54,
                "apple",
                8,
                "fruit",
                54,
                "leek",
                2,
                "vegetable",
                54,
                "cabbage",
                9,
                "vegetable",
                54,
                "lettuce",
                10,
                "vegetable",
                54,
                "kale",
                23,
                "vegetable",
                54)
            .getRows();

    PAssert.that(result).containsInAnyOrder(overResult);

    pipeline.run();
  }
}
