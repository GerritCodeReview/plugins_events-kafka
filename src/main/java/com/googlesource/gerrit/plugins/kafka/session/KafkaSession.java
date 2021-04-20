// Copyright (C) 2016 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.kafka.session;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaEventsPublisherMetrics;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KafkaSession {

  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaSession.class);
  private final KafkaProperties properties;
  private final Provider<KafkaProducer<String, String>> producerProvider;
  private final KafkaEventsPublisherMetrics publisherMetrics;
  private volatile Producer<String, String> producer;

  @Inject
  public KafkaSession(
      Provider<KafkaProducer<String, String>> producerProvider,
      KafkaProperties properties,
      KafkaEventsPublisherMetrics publisherMetrics) {
    this.producerProvider = producerProvider;
    this.properties = properties;
    this.publisherMetrics = publisherMetrics;
  }

  public boolean isOpen() {
    if (producer != null) {
      return true;
    }
    return false;
  }

  public void connect() {
    if (isOpen()) {
      LOGGER.debug("Already connected.");
      return;
    }

    LOGGER.info("Connect to {}...", properties.getProperty("bootstrap.servers"));
    /* Need to make sure that the thread of the running connection uses
     * the correct class loader otherwize you can endup with hard to debug
     * ClassNotFoundExceptions
     */
    setConnectionClassLoader();
    producer = producerProvider.get();
    LOGGER.info("Connection established.");
  }

  private void setConnectionClassLoader() {
    Thread.currentThread().setContextClassLoader(KafkaSession.class.getClassLoader());
  }

  public void disconnect() {
    LOGGER.info("Disconnecting...");
    if (producer != null) {
      LOGGER.info("Closing Producer {}...", producer);
      producer.close();
    }
    producer = null;
  }

  public void publish(String messageBody) {
    publish(properties.getTopic(), messageBody);
  }

  public boolean publish(String topic, String messageBody) {
    if (properties.isSendAsync()) {
      return publishAsync(topic, messageBody);
    }
    return publishSync(topic, messageBody);
  }

  private boolean publishSync(String topic, String messageBody) {

    try {
      Future<RecordMetadata> future =
          producer.send(new ProducerRecord<>(topic, "" + System.nanoTime(), messageBody));
      RecordMetadata metadata = future.get();
      LOGGER.debug("The offset of the record we just sent is: {}", metadata.offset());
      publisherMetrics.incrementBrokerPublishedMessage();
      return true;
    } catch (Throwable e) {
      LOGGER.error("Cannot send the message", e);
      publisherMetrics.incrementBrokerFailedToPublishMessage();
      return false;
    }
  }

  private boolean publishAsync(String topic, String messageBody) {
    try {
      Future<RecordMetadata> future =
          producer.send(
              new ProducerRecord<>(topic, Long.toString(System.nanoTime()), messageBody),
              (metadata, e) -> {
                if (metadata != null && e == null) {
                  LOGGER.debug("The offset of the record we just sent is: {}", metadata.offset());
                  publisherMetrics.incrementBrokerPublishedMessage();
                } else {
                  LOGGER.error("Cannot send the message", e);
                  publisherMetrics.incrementBrokerFailedToPublishMessage();
                }
              });
      return future != null;
    } catch (Throwable e) {
      LOGGER.error("Cannot send the message", e);
      publisherMetrics.incrementBrokerFailedToPublishMessage();
      return false;
    }
  }
}
