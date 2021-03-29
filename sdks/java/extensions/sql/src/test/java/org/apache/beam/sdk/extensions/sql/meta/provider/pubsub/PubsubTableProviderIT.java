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
package org.apache.beam.sdk.extensions.sql.meta.provider.pubsub;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.beam.sdk.testing.JsonMatcher.jsonBytesLike;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasProperty;
import org.apache.beam.sdk.extensions.protobuf.PayloadMessages;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.extensions.sql.impl.BeamSqlEnv;
import org.apache.beam.sdk.extensions.sql.impl.JdbcConnection;
import org.apache.beam.sdk.extensions.sql.impl.JdbcDriver;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamSqlRelUtils;
import org.apache.beam.sdk.extensions.sql.meta.provider.SchemaIOTableProviderWrapper;
import org.apache.beam.sdk.extensions.sql.meta.provider.TableProvider;
import org.apache.beam.sdk.extensions.sql.meta.store.InMemoryMetaStore;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.gcp.pubsub.TestPubsub;
import org.apache.beam.sdk.io.gcp.pubsub.TestPubsubSignal;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.SchemaCoder;
import org.apache.beam.sdk.schemas.utils.AvroUtils;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.calcite.v1_20_0.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.calcite.v1_20_0.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.calcite.v1_20_0.com.google.common.collect.ImmutableSet;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.jdbc.CalciteConnection;
import org.hamcrest.Matcher;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
@SuppressWarnings({
  "keyfor",
})
public class PubsubTableProviderIT implements Serializable {

  private static final Schema PAYLOAD_SCHEMA =
      Schema.builder()
          .addNullableField("id", Schema.FieldType.INT32)
          .addNullableField("name", Schema.FieldType.STRING)
          .build();

  @Rule public transient TestPubsub eventsTopic = TestPubsub.create();
  @Rule public transient TestPubsub filteredEventsTopic = TestPubsub.create();
  @Rule public transient TestPubsub dlqTopic = TestPubsub.create();
  @Rule public transient TestPubsubSignal resultSignal = TestPubsubSignal.create();
  @Rule public transient TestPipeline pipeline = TestPipeline.create();
  @Rule public transient TestPipeline filterPipeline = TestPipeline.create();
  private final SchemaIOTableProviderWrapper tableProvider = new PubsubTableProvider();

  @Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {{new PubsubJsonObjectProvider()}, {new PubsubAvroObjectProvider()}, {new PubsubProtoObjectProvider()}});
  }

  @Parameter public PubsubObjectProvider objectsProvider;

  /**
   * HACK: we need an objectmapper to turn pipelineoptions back into a map. We need to use
   * ReflectHelpers to get the extra PipelineOptions.
   */
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModules(ObjectMapper.findModules(ReflectHelpers.findClassLoader()));

  @Test
  public void testSQLSelectsPayloadContent() throws Exception {
    String createTableString =
        String.format(
            "CREATE EXTERNAL TABLE message (\n"
                + "event_timestamp TIMESTAMP, \n"
                + "attributes MAP<VARCHAR, VARCHAR>, \n"
                + "payload ROW< \n"
                + "             id INTEGER, \n"
                + "             name VARCHAR \n"
                + "           > \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "TBLPROPERTIES '{ "
                + "%s"
                + "\"timestampAttributeKey\" : \"ts\" }'",
            tableProvider.getTableType(), eventsTopic.topicPath(), payloadFormatParam());

    String queryString = "SELECT message.payload.id, message.payload.name from message";

    // Prepare messages to send later
    List<PubsubMessage> messages =
        ImmutableList.of(
            objectsProvider.messageIdName(ts(1), 3, "foo"),
            objectsProvider.messageIdName(ts(2), 5, "bar"),
            objectsProvider.messageIdName(ts(3), 7, "baz"));

    // Initialize SQL environment and create the pubsub table
    BeamSqlEnv sqlEnv = BeamSqlEnv.inMemory(new PubsubTableProvider());
    sqlEnv.executeDdl(createTableString);

    // Apply the PTransform to query the pubsub topic
    PCollection<Row> queryOutput = query(sqlEnv, pipeline, queryString);

    // Observe the query results and send success signal after seeing the expected messages
    queryOutput.apply(
        "waitForSuccess",
        resultSignal.signalSuccessWhen(
            SchemaCoder.of(PAYLOAD_SCHEMA),
            observedRows ->
                observedRows.equals(
                    ImmutableSet.of(
                        row(PAYLOAD_SCHEMA, 3, "foo"),
                        row(PAYLOAD_SCHEMA, 5, "bar"),
                        row(PAYLOAD_SCHEMA, 7, "baz")))));

    // Start the pipeline
    pipeline.run();

    // Block until a subscription for this topic exists
    eventsTopic.assertSubscriptionEventuallyCreated(
        pipeline.getOptions().as(GcpOptions.class).getProject(), Duration.standardMinutes(5));

    // Start publishing the messages when main pipeline is started and signaling topic is ready
    eventsTopic.publish(messages);

    // Poll the signaling topic for success message
    resultSignal.waitForSuccess(Duration.standardMinutes(5));
  }

  @Test
  public void testSQLSelectsArrayAttributes() throws Exception {
    String createTableString =
        String.format(
            "CREATE EXTERNAL TABLE message (\n"
                + "event_timestamp TIMESTAMP, \n"
                + "attributes ARRAY<ROW<key VARCHAR, `value` VARCHAR>>, \n"
                + "payload ROW< \n"
                + "             id INTEGER, \n"
                + "             name VARCHAR \n"
                + "           > \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "TBLPROPERTIES '{ "
                + "%s"
                + "\"timestampAttributeKey\" : \"ts\" }'",
            tableProvider.getTableType(), eventsTopic.topicPath(), payloadFormatParam());

    String queryString =
        "SELECT message.payload.id, attributes[1].key AS a1, attributes[2].key AS a2 FROM message";

    // Prepare messages to send later
    List<PubsubMessage> messages =
        ImmutableList.of(
            objectsProvider.messageIdName(ts(1), 3, "foo"),
            objectsProvider.messageIdName(ts(2), 5, "bar"),
            objectsProvider.messageIdName(ts(3), 7, "baz"));

    // Initialize SQL environment and create the pubsub table
    BeamSqlEnv sqlEnv = BeamSqlEnv.inMemory(new PubsubTableProvider());
    sqlEnv.executeDdl(createTableString);

    // Apply the PTransform to query the pubsub topic
    PCollection<Row> queryOutput = query(sqlEnv, pipeline, queryString);

    // Observe the query results and send success signal after seeing the expected messages
    queryOutput.apply(
        "waitForSuccess",
        resultSignal.signalSuccessWhen(
            SchemaCoder.of(PAYLOAD_SCHEMA),
            observedRows -> {
              Map<Integer, String> entries = new HashMap<>();
              for (Row row : observedRows) {
                if ("ts".equals(row.getString("a1"))) {
                  entries.put(row.getInt32("id"), row.getString("a2"));
                } else {
                  entries.put(row.getInt32("id"), row.getString("a1"));
                }
              }

              return entries.equals(ImmutableMap.of(3, "foo", 5, "bar", 7, "baz"));
            }));

    // Start the pipeline
    pipeline.run();

    // Block until a subscription for this topic exists
    eventsTopic.assertSubscriptionEventuallyCreated(
        pipeline.getOptions().as(GcpOptions.class).getProject(), Duration.standardMinutes(5));

    // Start publishing the messages when main pipeline is started and signaling topic is ready
    eventsTopic.publish(messages);

    // Poll the signaling topic for success message
    resultSignal.waitForSuccess(Duration.standardMinutes(1));
  }

  @Test
  public void testSQLWithBytePayload() throws Exception {
    String createTableString =
        String.format(
            "CREATE EXTERNAL TABLE message (\n"
                + "event_timestamp TIMESTAMP, \n"
                + "attributes MAP<VARCHAR, VARCHAR>, \n"
                + "payload VARBINARY \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "TBLPROPERTIES '{ "
                + "\"timestampAttributeKey\" : \"ts\" }'",
            tableProvider.getTableType(), eventsTopic.topicPath());

    String queryString = "SELECT message.payload AS some_bytes FROM message";

    // Prepare messages to send later
    List<PubsubMessage> messages =
        ImmutableList.of(
            objectsProvider.messageIdName(ts(1), 3, "foo"),
            objectsProvider.messageIdName(ts(2), 5, "bar"),
            objectsProvider.messageIdName(ts(3), 7, "baz"));

    // Initialize SQL environment and create the pubsub table
    BeamSqlEnv sqlEnv = BeamSqlEnv.inMemory(new PubsubTableProvider());
    sqlEnv.executeDdl(createTableString);

    // Apply the PTransform to query the pubsub topic
    PCollection<Row> queryOutput = query(sqlEnv, pipeline, queryString);

    // Observe the query results and send success signal after seeing the expected messages
    Schema justBytesSchema =
        Schema.builder().addField("some_bytes", FieldType.BYTES.withNullable(true)).build();
    Row expectedRow0 = row(justBytesSchema, (Object) messages.get(0).getPayload());
    Row expectedRow1 = row(justBytesSchema, (Object) messages.get(1).getPayload());
    Row expectedRow2 = row(justBytesSchema, (Object) messages.get(2).getPayload());
    Set<Row> expected = ImmutableSet.of(expectedRow0, expectedRow1, expectedRow2);
    queryOutput.apply(
        "waitForSuccess",
        resultSignal.signalSuccessWhen(
            SchemaCoder.of(justBytesSchema), observedRows -> observedRows.equals(expected)));

    // Start the pipeline
    pipeline.run();

    // Block until a subscription for this topic exists
    eventsTopic.assertSubscriptionEventuallyCreated(
        pipeline.getOptions().as(GcpOptions.class).getProject(), Duration.standardMinutes(5));

    // Start publishing the messages when main pipeline is started and signaling topic is ready
    eventsTopic.publish(messages);

    // Poll the signaling topic for success message
    resultSignal.waitForSuccess(Duration.standardMinutes(5));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testUsesDlq() throws Exception {
    String createTableString =
        String.format(
            "CREATE EXTERNAL TABLE message (\n"
                + "event_timestamp TIMESTAMP, \n"
                + "attributes MAP<VARCHAR, VARCHAR>, \n"
                + "payload ROW< \n"
                + "             id INTEGER, \n"
                + "             name VARCHAR \n"
                + "           > \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "TBLPROPERTIES "
                + "    '{ "
                + "       %s"
                + "       \"timestampAttributeKey\" : \"ts\", "
                + "       \"deadLetterQueue\" : \"%s\""
                + "     }'",
            tableProvider.getTableType(),
            eventsTopic.topicPath(),
            payloadFormatParam(),
            dlqTopic.topicPath());

    String queryString = "SELECT message.payload.id, message.payload.name from message";

    // Prepare messages to send later
    List<PubsubMessage> messages =
        ImmutableList.of(
            objectsProvider.messageIdName(ts(1), 3, "foo"),
            objectsProvider.messageIdName(ts(2), 5, "bar"),
            objectsProvider.messageIdName(ts(3), 7, "baz"),
            messagePayload(ts(4), "{ - }", ImmutableMap.of()), // invalid message, will go to DLQ
            messagePayload(ts(5), "{ + }", ImmutableMap.of())); // invalid message, will go to DLQ

    // Initialize SQL environment and create the pubsub table
    BeamSqlEnv sqlEnv = BeamSqlEnv.inMemory(new PubsubTableProvider());
    sqlEnv.executeDdl(createTableString);

    // Apply the PTransform to query the pubsub topic
    PCollection<Row> queryOutput = query(sqlEnv, pipeline, queryString);

    // Observe the query results and send success signal after seeing the expected messages
    queryOutput.apply(
        "waitForSuccess",
        resultSignal.signalSuccessWhen(
            SchemaCoder.of(PAYLOAD_SCHEMA),
            observedRows ->
                observedRows.equals(
                    ImmutableSet.of(
                        row(PAYLOAD_SCHEMA, 3, "foo"),
                        row(PAYLOAD_SCHEMA, 5, "bar"),
                        row(PAYLOAD_SCHEMA, 7, "baz")))));

    // Start the pipeline
    pipeline.run();

    // Block until a subscription for this topic exists
    eventsTopic.assertSubscriptionEventuallyCreated(
        pipeline.getOptions().as(GcpOptions.class).getProject(), Duration.standardMinutes(5));

    // Start publishing the messages when main pipeline is started and signaling topics are ready
    eventsTopic.publish(messages);

    // Poll the signaling topic for success message
    resultSignal.waitForSuccess(Duration.standardMinutes(4));
    dlqTopic
        .assertThatTopicEventuallyReceives(
            matcherPayload(ts(4), "{ - }"), matcherPayload(ts(5), "{ + }"))
        .waitForUpTo(Duration.standardSeconds(40));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void testSQLLimit() throws Exception {
    String createTableString =
        String.format(
            "CREATE EXTERNAL TABLE message (\n"
                + "event_timestamp TIMESTAMP, \n"
                + "attributes MAP<VARCHAR, VARCHAR>, \n"
                + "payload ROW< \n"
                + "             id INTEGER, \n"
                + "             name VARCHAR \n"
                + "           > \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "TBLPROPERTIES "
                + "    '{ "
                + "       %s"
                + "       \"timestampAttributeKey\" : \"ts\", "
                + "       \"deadLetterQueue\" : \"%s\""
                + "     }'",
            tableProvider.getTableType(),
            eventsTopic.topicPath(),
            payloadFormatParam(),
            dlqTopic.topicPath());

    List<PubsubMessage> messages =
        ImmutableList.of(
            objectsProvider.messageIdName(ts(1), 3, "foo"),
            objectsProvider.messageIdName(ts(2), 5, "bar"),
            objectsProvider.messageIdName(ts(3), 7, "baz"),
            objectsProvider.messageIdName(ts(4), 9, "ba2"),
            objectsProvider.messageIdName(ts(5), 10, "ba3"),
            objectsProvider.messageIdName(ts(6), 13, "ba4"),
            objectsProvider.messageIdName(ts(7), 15, "ba5"));

    // We need the default options on the schema to include the project passed in for the
    // integration test
    CalciteConnection connection = connect(pipeline.getOptions(), new PubsubTableProvider());

    Statement statement = connection.createStatement();
    statement.execute(createTableString);

    // Because Pubsub only allow new subscription receives message after the subscription is
    // created, eventsTopic.publish(messages) can only be called after statement.executeQuery.
    // However, because statement.executeQuery is a blocking call, it has to be put into a
    // seperate thread to execute.
    ExecutorService pool = Executors.newFixedThreadPool(1);
    Future<List<String>> queryResult =
        pool.submit(
            (Callable)
                () -> {
                  ResultSet resultSet =
                      statement.executeQuery("SELECT message.payload.id FROM message LIMIT 3");
                  ImmutableList.Builder<String> result = ImmutableList.builder();
                  while (resultSet.next()) {
                    result.add(resultSet.getString(1));
                  }
                  return result.build();
                });

    eventsTopic.assertSubscriptionEventuallyCreated(
        pipeline.getOptions().as(GcpOptions.class).getProject(), Duration.standardMinutes(5));
    eventsTopic.publish(messages);
    assertThat(queryResult.get(2, TimeUnit.MINUTES).size(), equalTo(3));
    pool.shutdown();
  }

  @Test
  public void testSQLSelectsPayloadContentFlat() throws Exception {
    String createTableString =
        String.format(
            "CREATE EXTERNAL TABLE message (\n"
                + "event_timestamp TIMESTAMP, \n"
                + "id INTEGER, \n"
                + "name VARCHAR \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "TBLPROPERTIES "
                + "    '{ "
                + "       %s"
                + "       \"timestampAttributeKey\" : \"ts\" "
                + "     }'",
            tableProvider.getTableType(), eventsTopic.topicPath(), payloadFormatParam());

    String queryString = "SELECT message.id, message.name from message";

    // Prepare messages to send later
    List<PubsubMessage> messages =
        ImmutableList.of(
            objectsProvider.messageIdName(ts(1), 3, "foo"),
            objectsProvider.messageIdName(ts(2), 5, "bar"),
            objectsProvider.messageIdName(ts(3), 7, "baz"));

    // Initialize SQL environment and create the pubsub table
    BeamSqlEnv sqlEnv = BeamSqlEnv.inMemory(new PubsubTableProvider());
    sqlEnv.executeDdl(createTableString);

    // Apply the PTransform to query the pubsub topic
    PCollection<Row> queryOutput = query(sqlEnv, pipeline, queryString);

    // Observe the query results and send success signal after seeing the expected messages
    queryOutput.apply(
        "waitForSuccess",
        resultSignal.signalSuccessWhen(
            SchemaCoder.of(PAYLOAD_SCHEMA),
            observedRows ->
                observedRows.equals(
                    ImmutableSet.of(
                        row(PAYLOAD_SCHEMA, 3, "foo"),
                        row(PAYLOAD_SCHEMA, 5, "bar"),
                        row(PAYLOAD_SCHEMA, 7, "baz")))));

    // Start the pipeline
    pipeline.run();

    // Block until a subscription for this topic exists
    eventsTopic.assertSubscriptionEventuallyCreated(
        pipeline.getOptions().as(GcpOptions.class).getProject(), Duration.standardMinutes(5));

    // Start publishing the messages when main pipeline is started and signaling topic is ready
    eventsTopic.publish(messages);

    // Poll the signaling topic for success message
    resultSignal.waitForSuccess(Duration.standardMinutes(5));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSQLInsertRowsToPubsubFlat() throws Exception {
    String createTableString =
        String.format(
            "CREATE EXTERNAL TABLE message (\n"
                + "event_timestamp TIMESTAMP, \n"
                + "name VARCHAR, \n"
                + "height INTEGER, \n"
                + "knowsJavascript BOOLEAN \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "TBLPROPERTIES "
                + "    '{ "
                + "       %s"
                + "       \"deadLetterQueue\" : \"%s\""
                + "     }'",
            tableProvider.getTableType(),
            eventsTopic.topicPath(),
            payloadFormatParam(),
            dlqTopic.topicPath());

    // Initialize SQL environment and create the pubsub table
    BeamSqlEnv sqlEnv = BeamSqlEnv.inMemory(new PubsubTableProvider());
    sqlEnv.executeDdl(createTableString);

    // TODO(BEAM-8741): Ideally we could write this query without specifying a column list, because
    //   it shouldn't be possible to write to event_timestamp when it's mapped to  publish time.
    String queryString =
        "INSERT INTO message (name, height, knowsJavascript) \n"
            + "VALUES \n"
            + "('person1', 80, TRUE), \n"
            + "('person2', 70, FALSE)";

    // Apply the PTransform to insert the rows
    query(sqlEnv, pipeline, queryString);

    pipeline.run().waitUntilFinish(Duration.standardMinutes(5));

    eventsTopic
        .assertThatTopicEventuallyReceives(
            objectsProvider.matcherNameHeightKnowsJS("person1", 80, true),
            objectsProvider.matcherNameHeightKnowsJS("person2", 70, false))
        .waitForUpTo(Duration.standardSeconds(40));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSQLInsertRowsToPubsubWithTimestampAttributeFlat() throws Exception {
    String createTableString =
        String.format(
            "CREATE EXTERNAL TABLE message (\n"
                + "  event_timestamp TIMESTAMP, \n"
                + "  name VARCHAR, \n"
                + "  height INTEGER, \n"
                + "  knowsJavascript BOOLEAN \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "TBLPROPERTIES "
                + "  '{ "
                + "     %s "
                + "     \"deadLetterQueue\" : \"%s\","
                + "     \"timestampAttributeKey\" : \"ts\""
                + "   }'",
            tableProvider.getTableType(),
            eventsTopic.topicPath(),
            payloadFormatParam(),
            dlqTopic.topicPath());

    // Initialize SQL environment and create the pubsub table
    BeamSqlEnv sqlEnv = BeamSqlEnv.inMemory(new PubsubTableProvider());
    sqlEnv.executeDdl(createTableString);

    String queryString =
        "INSERT INTO message "
            + "VALUES "
            + "(TIMESTAMP '1970-01-01 00:00:00.001', 'person1', 80, TRUE), "
            + "(TIMESTAMP '1970-01-01 00:00:00.002', 'person2', 70, FALSE)";
    query(sqlEnv, pipeline, queryString);

    pipeline.run().waitUntilFinish(Duration.standardMinutes(5));

    eventsTopic
        .assertThatTopicEventuallyReceives(
            matcherTsNameHeightKnowsJS(ts(1), "person1", 80, true),
            matcherTsNameHeightKnowsJS(ts(2), "person2", 70, false))
        .waitForUpTo(Duration.standardSeconds(40));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSQLReadAndWriteWithSameFlatTableDefinition() throws Exception {
    // This test verifies that the same pubsub table definition can be used for both reading and
    // writing
    // pipeline: Use SQL to insert data into `people`
    // filterPipeline: Use SQL to read from `people`, filter the rows, and write to
    // `javascript_people`

    String tblProperties =
        objectsProvider.getPayloadFormat() == null
            ? ""
            : String.format(
                "TBLPROPERTIES '{\"format\": \"%s\"}'", objectsProvider.getPayloadFormat());
    String createTableString =
        String.format(
            "CREATE EXTERNAL TABLE people (\n"
                + "event_timestamp TIMESTAMP, \n"
                + "name VARCHAR, \n"
                + "height INTEGER, \n"
                + "knowsJavascript BOOLEAN \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "%s",
            tableProvider.getTableType(), eventsTopic.topicPath(), tblProperties);

    String createFilteredTableString =
        String.format(
            "CREATE EXTERNAL TABLE javascript_people (\n"
                + "event_timestamp TIMESTAMP, \n"
                + "name VARCHAR, \n"
                + "height INTEGER \n"
                + ") \n"
                + "TYPE '%s' \n"
                + "LOCATION '%s' \n"
                + "%s",
            tableProvider.getTableType(), filteredEventsTopic.topicPath(), tblProperties);

    // Initialize SQL environment and create the pubsub table
    BeamSqlEnv sqlEnv = BeamSqlEnv.inMemory(new PubsubTableProvider());
    sqlEnv.executeDdl(createTableString);
    sqlEnv.executeDdl(createFilteredTableString);

    // TODO(BEAM-8741): Ideally we could write these queries without specifying a column list,
    // because
    //   it shouldn't be possible to write to event_timestamp when it's mapped to  publish time.
    String filterQueryString =
        "INSERT INTO javascript_people (name, height) (\n"
            + "  SELECT \n"
            + "    name, \n"
            + "    height \n"
            + "  FROM people \n"
            + "  WHERE knowsJavascript \n"
            + ")";

    String injectQueryString =
        "INSERT INTO people (name, height, knowsJavascript) VALUES \n"
            + "('person1', 80, TRUE),  \n"
            + "('person2', 70, FALSE), \n"
            + "('person3', 60, TRUE),  \n"
            + "('person4', 50, FALSE), \n"
            + "('person5', 40, TRUE)";

    // Apply the PTransform to do the filtering
    query(sqlEnv, filterPipeline, filterQueryString);

    // Apply the PTransform to inject the input data
    query(sqlEnv, pipeline, injectQueryString);

    // Start the filter pipeline and wait until it has started.
    filterPipeline.run();

    // Block until a subscription for this topic exists
    eventsTopic.assertSubscriptionEventuallyCreated(
        pipeline.getOptions().as(GcpOptions.class).getProject(), Duration.standardMinutes(5));

    // .. then run the injector pipeline
    pipeline.run().waitUntilFinish(Duration.standardMinutes(5));

    filteredEventsTopic
        .assertThatTopicEventuallyReceives(
            objectsProvider.matcherNameHeight("person1", 80),
            objectsProvider.matcherNameHeight("person3", 60),
            objectsProvider.matcherNameHeight("person5", 40))
        .waitForUpTo(Duration.standardMinutes(5));
  }

  @SuppressWarnings("unchecked")
  private CalciteConnection connect(PipelineOptions options, TableProvider... tableProviders) {
    // HACK: PipelineOptions should expose a prominent method to do this reliably
    // The actual options are in the "options" field of the converted map
    Map<String, String> argsMap =
        ((Map<String, Object>) MAPPER.convertValue(pipeline.getOptions(), Map.class).get("options"))
            .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> toArg(entry.getValue())));

    InMemoryMetaStore inMemoryMetaStore = new InMemoryMetaStore();
    for (TableProvider tableProvider : tableProviders) {
      inMemoryMetaStore.registerProvider(tableProvider);
    }

    JdbcConnection connection = JdbcDriver.connect(inMemoryMetaStore, options);
    connection.setPipelineOptionsMap(argsMap);
    return connection;
  }

  private static String toArg(Object o) {
    try {
      String jsonRepr = MAPPER.writeValueAsString(o);

      // String and enums are expected to be unquoted on the command line
      if (jsonRepr.startsWith("\"") && jsonRepr.endsWith("\"")) {
        return jsonRepr.substring(1, jsonRepr.length() - 1);
      } else {
        return jsonRepr;
      }
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private String payloadFormatParam() {
    return objectsProvider.getPayloadFormat() == null
        ? ""
        : String.format("\"format\" : \"%s\", ", objectsProvider.getPayloadFormat());
  }

  private PCollection<Row> query(BeamSqlEnv sqlEnv, TestPipeline pipeline, String queryString) {

    return BeamSqlRelUtils.toPCollection(pipeline, sqlEnv.parseQuery(queryString));
  }

  private Row row(Schema schema, Object... values) {
    return Row.withSchema(schema).addValues(values).build();
  }

  private static PubsubMessage message(
      Instant timestamp, byte[] payload, Map<String, String> attributes) {
    return new PubsubMessage(
        payload,
        ImmutableMap.<String, String>builder()
            .putAll(attributes)
            .put("ts", String.valueOf(timestamp.getMillis()))
            .build());
  }

  private Matcher<PubsubMessage> matcherTsNameHeightKnowsJS(
      Instant ts, String name, int height, boolean knowsJS) throws Exception {
    return allOf(
        objectsProvider.matcherNameHeightKnowsJS(name, height, knowsJS),
        hasProperty("attributeMap", hasEntry("ts", String.valueOf(ts.getMillis()))));
  }

  private Matcher<PubsubMessage> matcherPayload(Instant timestamp, String payload) {
    return allOf(
        hasProperty("payload", equalTo(payload.getBytes(StandardCharsets.US_ASCII))),
        hasProperty("attributeMap", hasEntry("ts", String.valueOf(timestamp.getMillis()))));
  }

  private Instant ts(long millis) {
    return Instant.ofEpochMilli(millis);
  }

  private PubsubMessage messagePayload(
      Instant timestamp, String payload, Map<String, String> attributes) {
    return message(timestamp, payload.getBytes(StandardCharsets.US_ASCII), attributes);
  }

  private abstract static class PubsubObjectProvider implements Serializable {
    protected abstract String getPayloadFormat();

    protected abstract PubsubMessage messageIdName(Instant timestamp, int id, String name)
        throws Exception;

    protected abstract Matcher<PubsubMessage> matcherNames(String name) throws Exception;

    protected abstract Matcher<PubsubMessage> matcherNameHeightKnowsJS(
        String name, int height, boolean knowsJS) throws Exception;

    protected abstract Matcher<PubsubMessage> matcherNameHeight(String name, int height)
        throws Exception;
  }

  private static class PubsubProtoObjectProvider extends PubsubObjectProvider {

    @Override
    protected String getPayloadFormat() {
      return "proto";
    }

    @Override
    protected PubsubMessage messageIdName(Instant timestamp, int id, String name) {
      PayloadMessages.SimpleMessage.Builder simpleMessage =
              PayloadMessages.SimpleMessage.newBuilder();

      simpleMessage.setId(id);
      simpleMessage.setName(name);

      return PubsubTableProviderIT.message(
              timestamp,
              simpleMessage.build().toByteArray(),
              ImmutableMap.of(name, Integer.toString(id)));
    }

    @Override
    protected Matcher<PubsubMessage> matcherNames(String name) throws IOException {

      PayloadMessages.NameMessage.Builder nameMessage = PayloadMessages.NameMessage.newBuilder();

      return hasProperty("payload", equalTo(nameMessage.build().toByteArray()));
    }

    @Override
    protected Matcher<PubsubMessage> matcherNameHeightKnowsJS(
            String name, int height, boolean knowsJS) throws IOException {

      PayloadMessages.NameHeightKnowsJSMessage.Builder nameHeightKnowsJSMessage =
              PayloadMessages.NameHeightKnowsJSMessage.newBuilder();

      return hasProperty("payload", equalTo(nameHeightKnowsJSMessage.build().toByteArray()));
    }

    @Override
    protected Matcher<PubsubMessage> matcherNameHeight(String name, int height) throws IOException {
      PayloadMessages.NameHeightMessage.Builder nameHeightMEssage =
              PayloadMessages.NameHeightMessage.newBuilder();

      return hasProperty("payload", equalTo(nameHeightMEssage.build().toByteArray()));
    }
  }

  private static class PubsubJsonObjectProvider extends PubsubObjectProvider {

    // Pubsub table provider should default to json
    @Override
    protected String getPayloadFormat() {
      return null;
    }

    @Override
    protected PubsubMessage messageIdName(Instant timestamp, int id, String name) {
      String jsonString = "{ \"id\" : " + id + ", \"name\" : \"" + name + "\" }";
      return message(timestamp, jsonString, ImmutableMap.of(name, Integer.toString(id)));
    }

    @Override
    protected Matcher<PubsubMessage> matcherNames(String name) throws IOException {
      return hasProperty("payload", toJsonByteLike(String.format("{\"name\":\"%s\"}", name)));
    }

    @Override
    protected Matcher<PubsubMessage> matcherNameHeightKnowsJS(
        String name, int height, boolean knowsJS) throws IOException {
      String jsonString =
          String.format(
              "{\"name\":\"%s\", \"height\": %s, \"knowsJavascript\": %s}", name, height, knowsJS);

      return hasProperty("payload", toJsonByteLike(jsonString));
    }

    @Override
    protected Matcher<PubsubMessage> matcherNameHeight(String name, int height) throws IOException {
      String jsonString = String.format("{\"name\":\"%s\", \"height\": %s}", name, height);
      return hasProperty("payload", toJsonByteLike(jsonString));
    }

    private PubsubMessage message(
        Instant timestamp, String jsonPayload, Map<String, String> attributes) {
      return PubsubTableProviderIT.message(timestamp, jsonPayload.getBytes(UTF_8), attributes);
    }

    private Matcher<byte[]> toJsonByteLike(String jsonString) throws IOException {
      return jsonBytesLike(jsonString);
    }
  }

  private static class PubsubAvroObjectProvider extends PubsubObjectProvider {
    private static final Schema NAME_HEIGHT_KNOWS_JS_SCHEMA =
        Schema.builder()
            .addNullableField("name", Schema.FieldType.STRING)
            .addNullableField("height", Schema.FieldType.INT32)
            .addNullableField("knowsJavascript", Schema.FieldType.BOOLEAN)
            .build();

    private static final Schema NAME_HEIGHT_SCHEMA =
        Schema.builder()
            .addNullableField("name", Schema.FieldType.STRING)
            .addNullableField("height", Schema.FieldType.INT32)
            .build();

    @Override
    protected String getPayloadFormat() {
      return "avro";
    }

    @Override
    protected PubsubMessage messageIdName(Instant timestamp, int id, String name)
        throws IOException {
      byte[] encodedRecord =
          createEncodedGenericRecord(
              PAYLOAD_SCHEMA,
              org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList.of(
                  id, name));
      return message(timestamp, encodedRecord, ImmutableMap.of(name, Integer.toString(id)));
    }

    @Override
    protected Matcher<PubsubMessage> matcherNames(String name) throws IOException {
      Schema schema = Schema.builder().addStringField("name").build();
      byte[] encodedRecord =
          createEncodedGenericRecord(
              schema,
              org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList.of(
                  name));
      return hasProperty("payload", equalTo(encodedRecord));
    }

    @Override
    protected Matcher<PubsubMessage> matcherNameHeight(String name, int height) throws IOException {
      byte[] encodedRecord =
          createEncodedGenericRecord(
              NAME_HEIGHT_SCHEMA,
              org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList.of(
                  name, height));
      return hasProperty("payload", equalTo(encodedRecord));
    }

    @Override
    protected Matcher<PubsubMessage> matcherNameHeightKnowsJS(
        String name, int height, boolean knowsJS) throws IOException {
      byte[] encodedRecord =
          createEncodedGenericRecord(
              NAME_HEIGHT_KNOWS_JS_SCHEMA,
              org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList.of(
                  name, height, knowsJS));
      return hasProperty("payload", equalTo(encodedRecord));
    }

    private byte[] createEncodedGenericRecord(Schema beamSchema, List<Object> values)
        throws IOException {
      org.apache.avro.Schema avroSchema = AvroUtils.toAvroSchema(beamSchema);
      GenericRecordBuilder builder = new GenericRecordBuilder(avroSchema);
      List<org.apache.avro.Schema.Field> fields = avroSchema.getFields();
      for (int i = 0; i < fields.size(); ++i) {
        builder.set(fields.get(i), values.get(i));
      }
      AvroCoder<GenericRecord> coder = AvroCoder.of(avroSchema);
      ByteArrayOutputStream out = new ByteArrayOutputStream();

      coder.encode(builder.build(), out);
      return out.toByteArray();
    }
  }
}
