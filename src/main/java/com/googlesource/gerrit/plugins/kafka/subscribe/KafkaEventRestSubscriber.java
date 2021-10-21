// Copyright (C) 2019 The Android Open Source Project
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
package com.googlesource.gerrit.plugins.kafka.subscribe;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.gerritforge.gerrit.eventbroker.EventMessage;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.kafka.broker.ConsumerExecutor;
import com.googlesource.gerrit.plugins.kafka.config.KafkaSubscriberProperties;
import com.googlesource.gerrit.plugins.kafka.rest.KafkaRestClient;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.Deserializer;

public class KafkaEventRestSubscriber implements KafkaEventSubscriber {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int DELAY_RECONNECT_AFTER_FAILURE_MSEC = 1000;

  private final OneOffRequestContext oneOffCtx;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private final Deserializer<EventMessage> valueDeserializer;
  private final KafkaSubscriberProperties configuration;
  private final ExecutorService executor;
  private final KafkaEventSubscriberMetrics subscriberMetrics;
  private final Gson gson;

  private java.util.function.Consumer<EventMessage> messageProcessor;
  private String topic;
  private AtomicBoolean resetOffset = new AtomicBoolean(false);
  private final KafkaRestClient restClient;

  private volatile ReceiverJob receiver;

  @Inject
  public KafkaEventRestSubscriber(
      KafkaSubscriberProperties configuration,
      Deserializer<EventMessage> valueDeserializer,
      OneOffRequestContext oneOffCtx,
      @ConsumerExecutor ExecutorService executor,
      KafkaEventSubscriberMetrics subscriberMetrics,
      KafkaRestClient.Factory restClientFactory) {

    this.configuration = configuration;
    this.oneOffCtx = oneOffCtx;
    this.executor = executor;
    this.subscriberMetrics = subscriberMetrics;
    this.valueDeserializer = valueDeserializer;
    gson = new Gson();
    this.restClient = restClientFactory.create(configuration);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.subscribe.KafkaEventSubscriber#subscribe(java.lang.String, java.util.function.Consumer)
   */
  @Override
  public void subscribe(String topic, java.util.function.Consumer<EventMessage> messageProcessor) {
    this.topic = topic;
    this.messageProcessor = messageProcessor;
    logger.atInfo().log(
        "Kafka consumer subscribing to topic alias [%s] for event topic [%s]", topic, topic);
    try {
      runReceiver();
    } catch (InterruptedException | ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  private void runReceiver() throws InterruptedException, ExecutionException {
    receiver = new ReceiverJob(configuration.getGroupId());
    executor.execute(receiver);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.subscribe.KafkaEventSubscriber#shutdown()
   */
  @Override
  public void shutdown() {
    try {
      closed.set(true);
      receiver.close();
    } catch (InterruptedException | ExecutionException | IOException e) {
      logger.atWarning().withCause(e).log("Unable to close receiver for topic=%s", topic);
    }
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.subscribe.KafkaEventSubscriber#getMessageProcessor()
   */
  @Override
  public java.util.function.Consumer<EventMessage> getMessageProcessor() {
    return messageProcessor;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.subscribe.KafkaEventSubscriber#getTopic()
   */
  @Override
  public String getTopic() {
    return topic;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.kafka.subscribe.KafkaEventSubscriber#resetOffset()
   */
  @Override
  public void resetOffset() {
    resetOffset.set(true);
  }

  private class ReceiverJob implements Runnable {
    private final ListenableFuture<URI> kafkaRestConsumerUri;
    private final ListenableFuture<?> kafkaSubscriber;

    public ReceiverJob(String consumerGroup) throws InterruptedException, ExecutionException {
      kafkaRestConsumerUri = createConsumer(consumerGroup);
      kafkaSubscriber = restClient.mapAsync(kafkaRestConsumerUri, this::subscribeToTopic);
      kafkaSubscriber.get();
    }

    public void close() throws InterruptedException, ExecutionException, IOException {
      restClient.mapAsync(kafkaRestConsumerUri, this::deleteConsumer).get();
      restClient.close();
    }

    @Override
    public void run() {
      try {
        consume();
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("Consumer loop of topic %s ended", topic);
      }
    }

    private void consume() throws InterruptedException, ExecutionException {
      try {
        while (!closed.get()) {
          if (resetOffset.getAndSet(false)) {
            unsupported();
          }

          ConsumerRecords<byte[], byte[]> records;

          records = restClient.mapAsync(kafkaRestConsumerUri, this::getRecords).get();
          records.forEach(
              consumerRecord -> {
                try (ManualRequestContext ctx = oneOffCtx.open()) {
                  EventMessage event =
                      valueDeserializer.deserialize(consumerRecord.topic(), consumerRecord.value());
                  messageProcessor.accept(event);
                } catch (Exception e) {
                  logger.atSevere().withCause(e).log(
                      "Malformed event '%s': [Exception: %s]",
                      new String(consumerRecord.value(), UTF_8));
                  subscriberMetrics.incrementSubscriberFailedToConsumeMessage();
                }
              });
        }
      } catch (WakeupException e) {
        // Ignore exception if closing
        if (!closed.get()) {
          logger.atSevere().withCause(e).log("Consumer loop of topic %s interrupted", topic);
          reconnectAfterFailure();
        }
      } catch (Exception e) {
        subscriberMetrics.incrementSubscriberFailedToPollMessages();
        logger.atSevere().withCause(e).log(
            "Existing consumer loop of topic %s because of a non-recoverable exception", topic);
        reconnectAfterFailure();
      } finally {
        restClient.mapAsync(kafkaRestConsumerUri, this::deleteConsumer).get();
      }
    }

    private ListenableFuture<ConsumerRecords<byte[], byte[]>> getRecords(URI consumerUri) {
      HttpGet getRecords = restClient.createGetRecords(consumerUri);
      return restClient.mapAsync(
          restClient.execute(getRecords, HttpStatus.SC_OK), this::convertRecords);
    }

    private ListenableFuture<HttpResponse> subscribeToTopic(URI consumerUri) {
      HttpPost post = restClient.createPostToSubscribe(consumerUri, topic);
      return restClient.execute(post);
    }

    private ListenableFuture<?> deleteConsumer(URI consumerUri) {
      HttpDelete delete = restClient.createDeleteToConsumer(consumerUri);
      return restClient.execute(delete);
    }

    private ListenableFuture<URI> createConsumer(String name) {
      HttpPost post = restClient.createPostToConsumer(name);
      return restClient.mapAsync(restClient.execute(post, HttpStatus.SC_OK), this::getConsumerUri);
    }

    private ListenableFuture<URI> getConsumerUri(HttpResponse response) {
      try (Reader contentReader =
          new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8)) {
        JsonObject responseJson = gson.fromJson(contentReader, JsonObject.class);
        URI consumerUri = new URI(responseJson.get("base_uri").getAsString());
        return Futures.immediateFuture(restClient.resolveURI(consumerUri.getPath()));
      } catch (UnsupportedOperationException | IOException | URISyntaxException e) {
        return Futures.immediateFailedFuture(e);
      }
    }

    private ListenableFuture<ConsumerRecords<byte[], byte[]>> convertRecords(
        HttpResponse response) {
      try (Reader bodyReader = new InputStreamReader(response.getEntity().getContent())) {
        JsonArray jsonRecords = gson.fromJson(bodyReader, JsonArray.class);
        if (jsonRecords.size() == 0) {
          return Futures.immediateFuture(new ConsumerRecords<>(Collections.emptyMap()));
        }

        Stream<ConsumerRecord<byte[], byte[]>> jsonObjects =
            StreamSupport.stream(jsonRecords.spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .map(this::jsonToConsumerRecords);

        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> records =
            jsonObjects.collect(Collectors.groupingBy(this::jsonRecordPartition));
        return Futures.immediateFuture(new ConsumerRecords<>(records));
      } catch (UnsupportedOperationException | IOException e) {
        return Futures.immediateFailedFuture(e);
      }
    }

    private ConsumerRecord<byte[], byte[]> jsonToConsumerRecords(JsonObject jsonRecord) {
      return new ConsumerRecord<>(
          jsonRecord.get("topic").getAsString(),
          jsonRecord.get("partition").getAsInt(),
          jsonRecord.get("offset").getAsLong(),
          jsonRecord.get("key").toString().getBytes(),
          jsonRecord.get("value").toString().getBytes());
    }

    private TopicPartition jsonRecordPartition(ConsumerRecord<byte[], byte[]> consumerRecord) {
      return new TopicPartition(topic, consumerRecord.partition());
    }

    private void reconnectAfterFailure() throws InterruptedException, ExecutionException {
      // Random delay with average of DELAY_RECONNECT_AFTER_FAILURE_MSEC
      // for avoiding hammering exactly at the same interval in case of failure
      long reconnectDelay =
          DELAY_RECONNECT_AFTER_FAILURE_MSEC / 2
              + new Random().nextInt(DELAY_RECONNECT_AFTER_FAILURE_MSEC);
      Thread.sleep(reconnectDelay);
      runReceiver();
    }
  }

  private <T> T unsupported() {
    throw new IllegalArgumentException("Unsupported method");
  }
}