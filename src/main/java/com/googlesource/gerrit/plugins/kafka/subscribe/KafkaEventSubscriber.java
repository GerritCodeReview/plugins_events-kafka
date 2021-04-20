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
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.kafka.broker.ConsumerExecutor;
import com.googlesource.gerrit.plugins.kafka.config.KafkaSubscriberProperties;
import java.time.Duration;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.Deserializer;

public class KafkaEventSubscriber {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int DELAY_RECONNECT_AFTER_FAILURE_MSEC = 1000;

  private final OneOffRequestContext oneOffCtx;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private final Deserializer<EventMessage> valueDeserializer;
  private final KafkaSubscriberProperties configuration;
  private final ExecutorService executor;
  private final KafkaEventSubscriberMetrics subscriberMetrics;
  private final KafkaConsumerFactory consumerFactory;
  private final Deserializer<byte[]> keyDeserializer;

  private java.util.function.Consumer<EventMessage> messageProcessor;
  private String topic;
  private AtomicBoolean resetOffset = new AtomicBoolean(false);

  private volatile ReceiverJob receiver;

  @Inject
  public KafkaEventSubscriber(
      KafkaSubscriberProperties configuration,
      KafkaConsumerFactory consumerFactory,
      Deserializer<byte[]> keyDeserializer,
      Deserializer<EventMessage> valueDeserializer,
      OneOffRequestContext oneOffCtx,
      @ConsumerExecutor ExecutorService executor,
      KafkaEventSubscriberMetrics subscriberMetrics) {

    this.configuration = configuration;
    this.oneOffCtx = oneOffCtx;
    this.executor = executor;
    this.subscriberMetrics = subscriberMetrics;
    this.consumerFactory = consumerFactory;
    this.keyDeserializer = keyDeserializer;
    this.valueDeserializer = valueDeserializer;
  }

  public void subscribe(String topic, java.util.function.Consumer<EventMessage> messageProcessor) {
    this.topic = topic;
    this.messageProcessor = messageProcessor;
    logger.atInfo().log(
        "Kafka consumer subscribing to topic alias [%s] for event topic [%s]", topic, topic);
    runReceiver();
  }

  private void runReceiver() {
    final ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(KafkaEventSubscriber.class.getClassLoader());
      Consumer<byte[], byte[]> consumer = consumerFactory.create(keyDeserializer);
      consumer.subscribe(Collections.singleton(topic));
      receiver = new ReceiverJob(consumer);
      executor.execute(receiver);
    } finally {
      Thread.currentThread().setContextClassLoader(previousClassLoader);
    }
  }

  public void shutdown() {
    closed.set(true);
    receiver.wakeup();
  }

  public java.util.function.Consumer<EventMessage> getMessageProcessor() {
    return messageProcessor;
  }

  public String getTopic() {
    return topic;
  }

  public void resetOffset() {
    resetOffset.set(true);
  }

  private class ReceiverJob implements Runnable {
    private final Consumer<byte[], byte[]> consumer;

    public ReceiverJob(Consumer<byte[], byte[]> consumer) {
      this.consumer = consumer;
    }

    public void wakeup() {
      consumer.wakeup();
    }

    @Override
    public void run() {
      try {
        consume();
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("Consumer loop of topic %s ended", topic);
      }
    }

    private void consume() throws InterruptedException {
      try {
        while (!closed.get()) {
          if (resetOffset.getAndSet(false)) {
            // Make sure there is an assignment for this consumer
            while (consumer.assignment().isEmpty() && !closed.get()) {
              logger.atInfo().log(
                  "Resetting offset: no partitions assigned to the consumer, request assignment.");
              consumer.poll(Duration.ofMillis(configuration.getPollingInterval()));
            }
            consumer.seekToBeginning(consumer.assignment());
          }
          ConsumerRecords<byte[], byte[]> consumerRecords =
              consumer.poll(Duration.ofMillis(configuration.getPollingInterval()));
          consumerRecords.forEach(
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
        consumer.close();
      }
    }

    private void reconnectAfterFailure() throws InterruptedException {
      // Random delay with average of DELAY_RECONNECT_AFTER_FAILURE_MSEC
      // for avoiding hammering exactly at the same interval in case of failure
      long reconnectDelay =
          DELAY_RECONNECT_AFTER_FAILURE_MSEC / 2
              + new Random().nextInt(DELAY_RECONNECT_AFTER_FAILURE_MSEC);
      Thread.sleep(reconnectDelay);
      runReceiver();
    }
  }
}
