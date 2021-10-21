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
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gerrit.httpd.ProxyProperties;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.kafka.broker.ConsumerExecutor;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import com.googlesource.gerrit.plugins.kafka.config.KafkaSubscriberProperties;
import com.googlesource.gerrit.plugins.kafka.publish.FutureExecutor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.config.RequestConfig.Builder;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class KafkaEventRestSubscriber implements KafkaEventSubscriber {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int DELAY_RECONNECT_AFTER_FAILURE_MSEC = 1000;
  private static final String KAFKA_V2_JSON = "application/vnd.kafka.json.v2+json";
  private static final String KAFKA_V2 = "application/vnd.kafka.v2+json";

  private final OneOffRequestContext oneOffCtx;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private final Deserializer<EventMessage> valueDeserializer;
  private final KafkaSubscriberProperties configuration;
  private final ExecutorService executor;
  private final KafkaEventSubscriberMetrics subscriberMetrics;
  private final Optional<HttpHost> proxy;
  private final URI kafkaRestApiUri;
  private final ExecutorService futureExecutor;
  private final CloseableHttpAsyncClient httpclient;
  private final Gson gson;

  private java.util.function.Consumer<EventMessage> messageProcessor;
  private String topic;
  private AtomicBoolean resetOffset = new AtomicBoolean(false);

  private volatile ReceiverJob receiver;

  @Inject
  public KafkaEventRestSubscriber(
      KafkaSubscriberProperties configuration,
      Deserializer<EventMessage> valueDeserializer,
      OneOffRequestContext oneOffCtx,
      @ConsumerExecutor ExecutorService executor,
      KafkaEventSubscriberMetrics subscriberMetrics,
      ProxyProperties proxyConf,
      @FutureExecutor ExecutorService futureExecutor) {

    this.configuration = configuration;
    this.oneOffCtx = oneOffCtx;
    this.executor = executor;
    this.subscriberMetrics = subscriberMetrics;
    this.valueDeserializer = valueDeserializer;
    this.proxy = getProxy(proxyConf);
    this.kafkaRestApiUri = getRestApi(configuration);
    this.futureExecutor = futureExecutor;
    httpclient = HttpAsyncClients.createDefault();
    gson = new Gson();

    Logger.getLogger("org.apache.http.wire").setLevel(Level.TRACE);
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
    } catch (InterruptedException | ExecutionException e) {
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
    private final ListenableFuture<Void> kafkaSubscriber;

    public ReceiverJob(String consumerGroup) throws InterruptedException, ExecutionException {
      kafkaRestConsumerUri = createConsumer(consumerGroup);
      kafkaSubscriber =
          Futures.transformAsync(kafkaRestConsumerUri, this::subscribeToTopic, futureExecutor);
      kafkaSubscriber.get();
    }

    public void close() throws InterruptedException, ExecutionException {
      Futures.transformAsync(kafkaRestConsumerUri, this::deleteConsumer, futureExecutor).get();
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
        kafkaSubscriber.get();
        while (!closed.get()) {
          if (resetOffset.getAndSet(false)) {
            unsupported();
          }

          ListenableFuture<ConsumerRecords<byte[], byte[]>> consumerRecords =
              Futures.transformAsync(kafkaRestConsumerUri, this::getRecords, futureExecutor);

          ConsumerRecords<byte[], byte[]> records;

          records = consumerRecords.get();
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
        Futures.transformAsync(kafkaRestConsumerUri, this::deleteConsumer, futureExecutor).get();
      }
    }

    private ListenableFuture<ConsumerRecords<byte[], byte[]>> getRecords(URI consumerUri) {
      httpclient.start();
      Builder configBuilder = RequestConfig.custom();
      configBuilder = proxy.map(configBuilder::setProxy).orElse(configBuilder);
      RequestConfig config = configBuilder.build();
      HttpGet post = createGetRecords(consumerUri);
      post.setConfig(config);
      return Futures.transformAsync(
          JdkFutureAdapters.listenInPoolThread(httpclient.execute(post, null), futureExecutor),
          this::convertRecords,
          futureExecutor);
    }

    private ListenableFuture<Void> subscribeToTopic(URI consumerUri) {
      httpclient.start();
      Builder configBuilder = RequestConfig.custom();
      configBuilder = proxy.map(configBuilder::setProxy).orElse(configBuilder);
      RequestConfig config = configBuilder.build();
      HttpPost post = createPostToSubscribe(consumerUri);
      post.setConfig(config);
      post.setEntity(
          new StringEntity(
              String.format("{\"topics\": [\"%s\"]}", topic),
              ContentType.create(KAFKA_V2, StandardCharsets.UTF_8)));
      return Futures.transformAsync(
          JdkFutureAdapters.listenInPoolThread(httpclient.execute(post, null), futureExecutor),
          this::checkResultOk,
          futureExecutor);
    }

    private ListenableFuture<Void> deleteConsumer(URI consumerUri) {
      httpclient.start();
      Builder configBuilder = RequestConfig.custom();
      configBuilder = proxy.map(configBuilder::setProxy).orElse(configBuilder);
      RequestConfig config = configBuilder.build();
      HttpDelete delete = createDeleteToConsumer(consumerUri);
      delete.setConfig(config);
      return Futures.transformAsync(
          JdkFutureAdapters.listenInPoolThread(httpclient.execute(delete, null), futureExecutor),
          this::checkResultOk,
          futureExecutor);
    }

    private ListenableFuture<URI> createConsumer(String name) {
      httpclient.start();
      Builder configBuilder = RequestConfig.custom();
      configBuilder = proxy.map(configBuilder::setProxy).orElse(configBuilder);
      RequestConfig config = configBuilder.build();
      HttpPost post = createPostToConsumer(name);
      post.setConfig(config);
      post.setEntity(
          new StringEntity(
              "{\"format\": \"json\", \"auto.offset.reset\": \"earliest\"}",
              ContentType.create(KAFKA_V2, StandardCharsets.UTF_8)));
      return Futures.transformAsync(
          JdkFutureAdapters.listenInPoolThread(httpclient.execute(post, null), futureExecutor),
          this::getConsumerUri,
          futureExecutor);
    }

    private ListenableFuture<URI> getConsumerUri(HttpResponse response) {
      switch (response.getStatusLine().getStatusCode()) {
        case HttpStatus.SC_OK:
          try (Reader contentReader =
              new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8)) {
            JsonObject responseJson = gson.fromJson(contentReader, JsonObject.class);
            URI consumerUri = new URI(responseJson.get("base_uri").getAsString());
            return Futures.immediateFuture(kafkaRestApiUri.resolve(consumerUri.getPath()));
          } catch (UnsupportedOperationException | IOException | URISyntaxException e) {
            return Futures.immediateFailedFuture(e);
          }
        default:
          return Futures.immediateFailedFuture(
              new IOException("Request failed: " + response.getStatusLine()));
      }
    }

    private ListenableFuture<Void> checkResultOk(HttpResponse response) {
      switch (response.getStatusLine().getStatusCode()) {
        case HttpStatus.SC_OK:
        case HttpStatus.SC_CREATED:
        case HttpStatus.SC_NO_CONTENT:
          return Futures.immediateFuture(null);
        default:
          return Futures.immediateFailedFuture(
              new IOException("Request failed: " + response.getStatusLine()));
      }
    }

    private ListenableFuture<ConsumerRecords<byte[], byte[]>> convertRecords(
        HttpResponse response) {
      switch (response.getStatusLine().getStatusCode()) {
        case HttpStatus.SC_OK:
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
        default:
          return Futures.immediateFailedFuture(
              new IOException("Request failed: " + response.getStatusLine()));
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

    private HttpPost createPostToConsumer(String name) {
      HttpPost post =
          new HttpPost(
              kafkaRestApiUri.resolve(
                  kafkaRestApiUri.getPath()
                      + "/consumers/"
                      + URLEncoder.encode(name, StandardCharsets.UTF_8)));
      post.addHeader(HttpHeaders.ACCEPT, "*/*");
      return post;
    }

    private HttpDelete createDeleteToConsumer(URI consumerUri) {
      HttpDelete delete = new HttpDelete(consumerUri);
      delete.addHeader(HttpHeaders.ACCEPT, "*/*");
      return delete;
    }

    private HttpPost createPostToSubscribe(URI consumerUri) {
      HttpPost post = new HttpPost(consumerUri.resolve(consumerUri.getPath() + "/subscription"));
      post.addHeader(HttpHeaders.ACCEPT, "*/*");
      return post;
    }

    private HttpGet createGetRecords(URI consumerUri) {
      HttpGet get = new HttpGet(consumerUri.resolve(consumerUri.getPath() + "/records"));
      get.addHeader(HttpHeaders.ACCEPT, KAFKA_V2_JSON);
      return get;
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

  private static Optional<HttpHost> getProxy(ProxyProperties proxyConf) {
    return Optional.ofNullable(proxyConf.getProxyUrl())
        .map((url) -> url.toString())
        .map(HttpHost::create);
  }

  private static URI getRestApi(KafkaProperties kafkaConf) {
    return kafkaConf.restApiUri();
  }

  private <T> T unsupported() {
    throw new IllegalArgumentException("Unsupported method");
  }
}
