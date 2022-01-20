// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.kafka.api;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.gerritforge.gerrit.eventbroker.EventGsonProvider;
import com.gerritforge.gerrit.eventbroker.EventMessage;
import com.gerritforge.gerrit.eventbroker.EventMessage.Header;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.util.IdGenerator;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.googlesource.gerrit.plugins.kafka.KafkaContainerProvider;
import com.googlesource.gerrit.plugins.kafka.KafkaRestContainer;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties.ClientType;
import com.googlesource.gerrit.plugins.kafka.config.KafkaSubscriberProperties;
import com.googlesource.gerrit.plugins.kafka.session.KafkaProducerProvider;
import com.googlesource.gerrit.plugins.kafka.session.KafkaSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.junit.MockitoJUnitRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;

@RunWith(MockitoJUnitRunner.class)
public class KafkaBrokerApiTest {

  static KafkaContainer kafka;
  static KafkaRestContainer kafkaRest;
  static KafkaRestContainer kafkaRestWithId;
  static GenericContainer<?> nginx;

  static final int TEST_NUM_SUBSCRIBERS = 1;
  static final String TEST_GROUP_ID = KafkaBrokerApiTest.class.getName();
  static final int TEST_POLLING_INTERVAL_MSEC = 100;
  static final String KAFKA_REST_ID = "kafka-rest-instance-0";
  private static final int TEST_THREAD_POOL_SIZE = 10;
  private static final UUID TEST_INSTANCE_ID = UUID.randomUUID();
  private static final TimeUnit TEST_TIMEOUT_UNIT = TimeUnit.SECONDS;
  private static final int TEST_TIMEOUT = 30;
  private static final int TEST_WAIT_FOR_MORE_MESSAGES_TIMEOUT = 5;

  private Injector injector;
  private KafkaSession session;
  private Gson gson;
  protected ClientType clientType;

  public static class TestWorkQueue extends WorkQueue {

    @Inject
    public TestWorkQueue(IdGenerator idGenerator, MetricMaker metrics) {
      super(idGenerator, TEST_THREAD_POOL_SIZE, metrics);
    }
  }

  public static class TestModule extends AbstractModule {
    private KafkaProperties kafkaProperties;

    public TestModule(KafkaProperties kafkaProperties) {
      this.kafkaProperties = kafkaProperties;
    }

    @Override
    protected void configure() {
      bind(Gson.class).toProvider(EventGsonProvider.class).in(Singleton.class);
      bind(MetricMaker.class).toInstance(mock(MetricMaker.class, Answers.RETURNS_DEEP_STUBS));
      bind(OneOffRequestContext.class)
          .toInstance(mock(OneOffRequestContext.class, Answers.RETURNS_DEEP_STUBS));

      bind(KafkaProperties.class).toInstance(kafkaProperties);
      bind(KafkaSession.class).in(Scopes.SINGLETON);

      bindKafkaClientImpl();

      bind(WorkQueue.class).to(TestWorkQueue.class);
    }

    protected void bindKafkaClientImpl() {
      bind(new TypeLiteral<Producer<String, String>>() {}).toProvider(KafkaProducerProvider.class);
      KafkaSubscriberProperties kafkaSubscriberProperties =
          new KafkaSubscriberProperties(
              TEST_POLLING_INTERVAL_MSEC, TEST_GROUP_ID, TEST_NUM_SUBSCRIBERS, ClientType.NATIVE);
      bind(KafkaSubscriberProperties.class).toInstance(kafkaSubscriberProperties);
    }
  }

  public static class TestConsumer implements Consumer<EventMessage> {
    public final List<EventMessage> messages = new ArrayList<>();
    private CountDownLatch lock;

    public TestConsumer(int numMessagesExpected) {
      resetExpectedMessages(numMessagesExpected);
    }

    public void resetExpectedMessages(int numMessagesExpected) {
      lock = new CountDownLatch(numMessagesExpected);
    }

    @Override
    public void accept(EventMessage message) {
      messages.add(message);
      lock.countDown();
    }

    public boolean await() {
      return await(TEST_TIMEOUT, TEST_TIMEOUT_UNIT);
    }

    public boolean await(long timeout, TimeUnit unit) {
      try {
        return lock.await(timeout, unit);
      } catch (InterruptedException e) {
        return false;
      }
    }
  }

  public static class TestHeader extends Header {

    public TestHeader() {
      super(UUID.randomUUID(), TEST_INSTANCE_ID);
    }
  }

  @BeforeClass
  public static void beforeClass() throws Exception {
    kafka = KafkaContainerProvider.get();
    kafka.start();
    kafkaRestWithId = new KafkaRestContainer(kafka, KAFKA_REST_ID);
    kafkaRestWithId.start();
    kafkaRest = new KafkaRestContainer(kafka);
    kafkaRest.start();

    System.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
  }

  @Before
  public void setup() {
    clientType = ClientType.NATIVE;
  }

  @AfterClass
  public static void afterClass() {
    stopContainer(kafka);
    stopContainer(kafkaRest);
    stopContainer(kafkaRestWithId);
    stopContainer(nginx);
  }

  private static void stopContainer(GenericContainer<?> container) {
    if (container != null) {
      container.stop();
    }
  }

  protected TestModule newTestModule(KafkaProperties kafkaProperties) {
    return new TestModule(kafkaProperties);
  }

  public void connectToKafka(KafkaProperties kafkaProperties) {
    Injector baseInjector = Guice.createInjector(newTestModule(kafkaProperties));
    WorkQueue testWorkQueue = baseInjector.getInstance(WorkQueue.class);
    KafkaSubscriberProperties kafkaSubscriberProperties =
        baseInjector.getInstance(KafkaSubscriberProperties.class);
    injector =
        baseInjector.createChildInjector(
            new KafkaApiModule(testWorkQueue, kafkaSubscriberProperties));
    session = injector.getInstance(KafkaSession.class);
    gson = injector.getInstance(Gson.class);

    session.connect();
  }

  @After
  public void teardown() {
    if (session != null) {
      session.disconnect();
    }
  }

  @Test
  public void shouldSendSyncAndReceiveToTopic() {
    connectToKafka(new KafkaProperties(false, clientType, getKafkaRestApiUriString()));
    KafkaBrokerApi kafkaBrokerApi = injector.getInstance(KafkaBrokerApi.class);
    String testTopic = "test_topic_sync";
    TestConsumer testConsumer = new TestConsumer(1);
    EventMessage testEventMessage = new EventMessage(new TestHeader(), new ProjectCreatedEvent());

    kafkaBrokerApi.receiveAsync(testTopic, testConsumer);
    kafkaBrokerApi.send(testTopic, testEventMessage);

    assertThat(testConsumer.await()).isTrue();
    assertThat(testConsumer.messages).hasSize(1);
    assertThat(gson.toJson(testConsumer.messages.get(0))).isEqualTo(gson.toJson(testEventMessage));

    assertNoMoreExpectedMessages(testConsumer);
  }

  @Test
  public void shouldSendAsyncAndReceiveToTopic() {
    connectToKafka(new KafkaProperties(true, clientType, getKafkaRestApiUriString()));
    KafkaBrokerApi kafkaBrokerApi = injector.getInstance(KafkaBrokerApi.class);
    String testTopic = "test_topic_async";
    TestConsumer testConsumer = new TestConsumer(1);
    EventMessage testEventMessage = new EventMessage(new TestHeader(), new ProjectCreatedEvent());

    kafkaBrokerApi.send(testTopic, testEventMessage);
    kafkaBrokerApi.receiveAsync(testTopic, testConsumer);

    assertThat(testConsumer.await()).isTrue();
    assertThat(testConsumer.messages).hasSize(1);
    assertThat(gson.toJson(testConsumer.messages.get(0))).isEqualTo(gson.toJson(testEventMessage));

    assertNoMoreExpectedMessages(testConsumer);
  }

  @Test
  public void shouldSendToTopicAndResetOffset() {
    connectToKafka(new KafkaProperties(false, clientType, getKafkaRestApiUriString()));
    KafkaBrokerApi kafkaBrokerApi = injector.getInstance(KafkaBrokerApi.class);
    String testTopic = "test_topic_reset";
    TestConsumer testConsumer = new TestConsumer(1);
    EventMessage testEventMessage = new EventMessage(new TestHeader(), new ProjectCreatedEvent());

    kafkaBrokerApi.receiveAsync(testTopic, testConsumer);
    kafkaBrokerApi.send(testTopic, testEventMessage);

    assertThat(testConsumer.await()).isTrue();
    assertThat(testConsumer.messages).hasSize(1);
    assertThat(gson.toJson(testConsumer.messages.get(0))).isEqualTo(gson.toJson(testEventMessage));

    kafkaBrokerApi.replayAllEvents(testTopic);

    assertThat(testConsumer.await()).isTrue();
    assertThat(testConsumer.messages).hasSize(1);
    assertThat(gson.toJson(testConsumer.messages.get(0))).isEqualTo(gson.toJson(testEventMessage));
  }

  protected String getKafkaRestApiUriString() {
    return null;
  }

  private void assertNoMoreExpectedMessages(TestConsumer testConsumer) {
    testConsumer.resetExpectedMessages(1);
    assertThat(testConsumer.await(TEST_WAIT_FOR_MORE_MESSAGES_TIMEOUT, TEST_TIMEOUT_UNIT))
        .isFalse();
  }
}
