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
package org.apache.beam.sdk.io.rabbitmq;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Method;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.common.NetworkTestHelper;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Throwables;
import org.apache.qpid.server.SystemLauncher;
import org.apache.qpid.server.model.SystemConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test of {@link RabbitMqIO}. */
@RunWith(JUnit4.class)
public class RabbitMqIOTest implements Serializable {
  private static final Logger LOG = LoggerFactory.getLogger(RabbitMqIOTest.class);

  private static final int ONE_MINUTE_MS = 60 * 1000;

  private static int port;
  private static String defaultPort;
  private static String uri;
  private static ConnectionHandler connectionHandler;

  @ClassRule public static TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule public transient TestPipeline p = TestPipeline.create();

  private static transient SystemLauncher launcher;

  @BeforeClass
  public static void beforeClass() throws Exception {
    port = NetworkTestHelper.getAvailableLocalPort();

    defaultPort = System.getProperty("qpid.amqp_port");
    System.setProperty("qpid.amqp_port", "" + port);

    System.setProperty("derby.stream.error.field", "MyApp.DEV_NULL");

    // see https://stackoverflow.com/a/49234754/796064 for qpid setup
    launcher = new SystemLauncher();

    Map<String, Object> attributes = new HashMap<>();
    URL initialConfig = RabbitMqIOTest.class.getResource("rabbitmq-io-test-config.json");
    attributes.put("type", "Memory");
    attributes.put("initialConfigurationLocation", initialConfig.toExternalForm());
    attributes.put(SystemConfig.DEFAULT_QPID_WORK_DIR, temporaryFolder.newFolder().toString());

    launcher.startup(attributes);

    uri = "amqp://guest:guest@localhost:" + port;

    connectionHandler = new ConnectionHandler(uri);
  }

  @AfterClass
  public static void afterClass() {
    if (defaultPort != null) {
      System.setProperty("qpid.amqp_port", defaultPort);
    } else {
      System.clearProperty("qpid.amqp_port");
    }
    launcher.shutdown();
    try {
      connectionHandler.close();
    } catch (IOException e) {
      /* ignored */
    }
  }

  @Test(timeout = ONE_MINUTE_MS * 100L)
  public void testReadDefaultExchange() throws Exception {
    UUID testId = UUID.randomUUID();

    final int maxNumRecords = 10;
    final String queueName = "READ";
    RabbitMqIO.Read spec =
        RabbitMqIO.read(uri)
            .withDefaultExchange()
            .withQueue(queueName, false)
            .withMaxNumRecords(maxNumRecords);

    PCollection<RabbitMqMessage> raw = p.apply(spec);

    PCollection<String> output =
        raw.apply(
            MapElements.into(TypeDescriptors.strings()).via(RabbitMqTestUtils::messageToString));

    final List<RabbitMqMessage> messages =
        RabbitMqTestUtils.generateRecords(maxNumRecords).stream()
            .map(msg -> msg.toBuilder().setRoutingKey(queueName).build())
            .collect(Collectors.toList());
    Set<String> expected =
        messages.stream().map(RabbitMqTestUtils::messageToString).collect(Collectors.toSet());

    PAssert.that(output).containsInAnyOrder(expected);

    try {
      connectionHandler.useChannel(testId, channel -> {
        RabbitMqTestUtils.createExchange(spec).apply(channel);
        channel.queueDeclare(queueName, false, false, false, Collections.emptyMap());
        RabbitMqTestUtils.publishMessages(spec, messages).apply(channel);
        return null;
      });
      p.run();
    } finally {
      connectionHandler.closeChannel(testId);
    }
  }

  /**
   * Helper for running tests against an exchange.
   *
   * <p>This function will automatically specify (and overwrite) the uri and numRecords values of
   * the Read definition.
   */
  private void doExchangeTest(ExchangeTestPlan testPlan, boolean simulateIncompatibleExchange)
      throws Exception {
    RabbitMqIO.Read read = testPlan.getRead();
    PCollection<RabbitMqMessage> raw =
        p.apply(read.withUri(uri).withMaxNumRecords(testPlan.getNumRecords()));

    PCollection<String> result =
        raw.apply(
            MapElements.into(TypeDescriptors.strings())
                .via((RabbitMqMessage message) -> RabbitMqTestUtils.bodyToString(message.body())));

    List<String> expected = testPlan.expectedResults();

    PAssert.that(result).containsInAnyOrder(expected);

    // on UUID fallback: tests appear to execute concurrently in jenkins, so
    // exchanges and queues between tests must be distinct
    String exchange =
        Optional.ofNullable(read.exchange()).orElseGet(() -> UUID.randomUUID().toString());
    String exchangeType = Optional.ofNullable(read.exchangeType()).orElse("fanout");
    if (simulateIncompatibleExchange) {
      // Rabbit will fail when attempting to declare an existing exchange that
      // has different properties (e.g. declaring a non-durable exchange if
      // an existing one is durable). QPid does not exhibit this behavior. To
      // simulate the error condition where RabbitMqIO attempts to declare an
      // incompatible exchange, we instead declare an exchange with the same
      // name but of a different type. Both Rabbit and QPid will fail this.
      if ("fanout".equalsIgnoreCase(exchangeType)) {
        exchangeType = "direct";
      } else {
        exchangeType = "fanout";
      }
    }

    ConnectionFactory connectionFactory = new ConnectionFactory();
    connectionFactory.setAutomaticRecoveryEnabled(false);
    connectionFactory.setUri(uri);
    Connection connection = null;
    Channel channel = null;

    try {
      connection = connectionFactory.newConnection();
      channel = connection.createChannel();
      channel.exchangeDeclare(exchange, exchangeType);
      final Channel finalChannel = channel;
      Thread publisher =
          new Thread(
              () -> {
                try {
                  Thread.sleep(5000);
                } catch (Exception e) {
                  LOG.error(e.getMessage(), e);
                }
                for (int i = 0; i < testPlan.getNumRecordsToPublish(); i++) {
                  try {
                    finalChannel.basicPublish(
                        exchange,
                        testPlan.publishRoutingKeyGen().get(),
                        testPlan.getPublishProperties(),
                        RabbitMqTestUtils.generateRecord(i).body());
                  } catch (Exception e) {
                    LOG.error(e.getMessage(), e);
                  }
                }
              });
      publisher.start();
      p.run();
      publisher.join();
    } finally {
      if (channel != null) {
        // channel may have already been closed automatically due to protocol failure
        try {
          channel.close();
        } catch (Exception e) {
          /* ignored */
        }
      }
      if (connection != null) {
        // connection may have already been closed automatically due to protocol failure
        try {
          connection.close();
        } catch (Exception e) {
          /* ignored */
        }
      }
    }
  }

  private void doExchangeTest(ExchangeTestPlan testPlan) throws Exception {
    doExchangeTest(testPlan, false);
  }

  @Test(timeout = ONE_MINUTE_MS)
  public void testReadDeclaredFanoutExchange() throws Exception {
    doExchangeTest(
        new ExchangeTestPlan(
            RabbitMqIO.read(uri).withFanoutExchange("DeclaredFanoutExchange"), 10));
  }

  @Test(timeout = ONE_MINUTE_MS)
  public void testReadDeclaredTopicExchangeWithQueueDeclare() throws Exception {
    doExchangeTest(
        new ExchangeTestPlan(
            RabbitMqIO.read(uri)
                .withTopicExchange("DeclaredTopicExchangeWithQueueDeclare", "#")
                .withQueue("declared-queue", true),
            10));
  }

  @Test(timeout = ONE_MINUTE_MS)
  public void testReadDeclaredTopicExchange() throws Exception {
    final int numRecords = 10;
    RabbitMqIO.Read read =
        RabbitMqIO.read(uri).withTopicExchange("DeclaredTopicExchange", "user.create.#");

    final Supplier<String> publishRoutingKeyGen =
        new Supplier<String>() {
          private AtomicInteger counter = new AtomicInteger(0);

          @Override
          public String get() {
            int count = counter.getAndIncrement();
            if (count % 2 == 0) {
              return "user.create." + count;
            }
            return "user.delete." + count;
          }
        };

    ExchangeTestPlan plan =
        new ExchangeTestPlan(read, numRecords / 2, numRecords) {
          @Override
          public Supplier<String> publishRoutingKeyGen() {
            return publishRoutingKeyGen;
          }

          @Override
          public List<String> expectedResults() {
            return IntStream.range(0, numRecords)
                .filter(i -> i % 2 == 0)
                .mapToObj(RabbitMqTestUtils::generateRecord)
                .map(RabbitMqTestUtils::messageToString)
                .collect(Collectors.toList());
          }
        };

    doExchangeTest(plan);
  }

  @Test(timeout = ONE_MINUTE_MS)
  public void testDeclareIncompatibleExchangeFails() throws Exception {
    RabbitMqIO.Read read = RabbitMqIO.read(uri).withDirectExchange("IncompatibleExchange");
    try {
      doExchangeTest(new ExchangeTestPlan(read, 1), true);
      fail("Expected to have failed to declare an incompatible exchange");
    } catch (Exception e) {
      Throwable cause = Throwables.getRootCause(e);
      if (cause instanceof ShutdownSignalException) {
        ShutdownSignalException sse = (ShutdownSignalException) cause;
        Method reason = sse.getReason();
        if (reason instanceof com.rabbitmq.client.AMQP.Connection.Close) {
          com.rabbitmq.client.AMQP.Connection.Close close =
              (com.rabbitmq.client.AMQP.Connection.Close) reason;
          assertEquals("Expected failure is 530: not-allowed", 530, close.getReplyCode());
        } else {
          fail(
              "Unexpected ShutdownSignalException reason. Expected Connection.Close. Got: "
                  + reason);
        }
      } else {
        fail("Expected to fail with ShutdownSignalException. Instead failed with " + cause);
      }
    }
  }

  @Test(timeout = ONE_MINUTE_MS)
  public void testUseCorrelationIdSucceedsWhenIdsPresent() throws Exception {
    int messageCount = 1;
    AMQP.BasicProperties publishProps =
        new AMQP.BasicProperties().builder().correlationId("123").build();
    doExchangeTest(
        new ExchangeTestPlan(
            RabbitMqIO.read(uri)
                .withFanoutExchange("CorrelationIdSuccess")
                .withRecordIdPolicy(RecordIdPolicy.correlationId()),
            messageCount,
            messageCount,
            publishProps));
  }

  @Test(expected = Pipeline.PipelineExecutionException.class)
  public void testUseRecordIdFailsWhenIdsMissing() throws Exception {
    int messageCount = 1;
    AMQP.BasicProperties publishProps = null;
    doExchangeTest(
        new ExchangeTestPlan(
            RabbitMqIO.read(uri)
                .withFanoutExchange("CorrelationIdFailure")
                .withRecordIdPolicy(RecordIdPolicy.messageId()),
            messageCount,
            messageCount,
            publishProps));
  }

  @Test
  public void testWriteQueue() throws Exception {
    final int maxNumRecords = 1000;
    List<RabbitMqMessage> data =
        RabbitMqTestUtils.generateRecords(maxNumRecords).stream()
            .map(msg -> msg.toBuilder().setRoutingKey("TEST").build())
            .collect(Collectors.toList());
    p.apply(Create.of(data)).apply(RabbitMqIO.write(uri));

    final List<String> received = new ArrayList<>();
    ConnectionFactory connectionFactory = new ConnectionFactory();
    connectionFactory.setUri("amqp://guest:guest@localhost:" + port);
    Connection connection = null;
    Channel channel = null;
    try {
      connection = connectionFactory.newConnection();
      channel = connection.createChannel();
      channel.queueDeclare("TEST", true, false, false, null);
      Consumer consumer = new RabbitMqTestUtils.TestConsumer(channel, received);
      channel.basicConsume("TEST", true, consumer);

      p.run();

      while (received.size() < maxNumRecords) {
        Thread.sleep(500);
      }

      assertEquals(maxNumRecords, received.size());
      for (int i = 0; i < maxNumRecords; i++) {
        assertTrue(received.contains("Test " + i));
      }
    } finally {
      if (channel != null) {
        channel.close();
      }
      if (connection != null) {
        connection.close();
      }
    }
  }

  @Test
  public void testWriteExchange() throws Exception {
    final int maxNumRecords = 1000;
    List<RabbitMqMessage> data = RabbitMqTestUtils.generateRecords(maxNumRecords);
    p.apply(Create.of(data)).apply(RabbitMqIO.write(uri).withExchange("WRITE"));

    final List<String> received = new ArrayList<>();
    ConnectionFactory connectionFactory = new ConnectionFactory();
    connectionFactory.setUri("amqp://guest:guest@localhost:" + port);
    Connection connection = null;
    Channel channel = null;
    try {
      connection = connectionFactory.newConnection();
      channel = connection.createChannel();
      channel.exchangeDeclare("WRITE", "fanout");
      String queueName = channel.queueDeclare().getQueue();
      channel.queueBind(queueName, "WRITE", "");
      Consumer consumer = new RabbitMqTestUtils.TestConsumer(channel, received);
      channel.basicConsume(queueName, true, consumer);

      p.run();

      while (received.size() < maxNumRecords) {
        Thread.sleep(500);
      }

      assertEquals(maxNumRecords, received.size());
      for (int i = 0; i < maxNumRecords; i++) {
        assertTrue(received.contains("Test " + i));
      }
    } finally {
      if (channel != null) {
        channel.close();
      }
      if (connection != null) {
        connection.close();
      }
    }
  }
}
