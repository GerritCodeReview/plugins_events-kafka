// Copyright (C) 2021 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.kafka.publish;

import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ProducerFencedException;

public class KafkaRestProducer implements Producer<String, String> {
  private static final RecordMetadata ZEROS_RECORD_METADATA =
      new RecordMetadata(null, 0, 0, 0, null, 0, 0);
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String KAFKA_V2_JSON = "application/vnd.kafka.json.v2+json";
  private final URI kafkaRestApi;
  private final ExecutorService futureExecutor;
  private final CloseableHttpAsyncClient httpclient;
  private RequestConfig httpRequestConf;

  @Inject
  public KafkaRestProducer(
      KafkaProperties kafkaConf,
      RequestConfig httpRequestConf,
      @FutureExecutor ExecutorService futureExecutor) {
    this.kafkaRestApi = kafkaConf.getRestApiUri();
    this.futureExecutor = futureExecutor;
    httpclient = HttpAsyncClients.createDefault();
    this.httpRequestConf = httpRequestConf;
  }

  @Override
  public void initTransactions() {
    unsupported();
  }

  @Override
  public void beginTransaction() throws ProducerFencedException {
    unsupported();
  }

  @Override
  public void sendOffsetsToTransaction(
      Map<TopicPartition, OffsetAndMetadata> offsets, String consumerGroupId)
      throws ProducerFencedException {
    unsupported();
  }

  @Override
  public void commitTransaction() throws ProducerFencedException {
    unsupported();
  }

  @Override
  public void abortTransaction() throws ProducerFencedException {
    unsupported();
  }

  @Override
  public Future<RecordMetadata> send(ProducerRecord<String, String> record) {
    return send(record, null);
  }

  @Override
  public Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
    httpclient.start();
    HttpPost post =
        createPostToTopic(
            record.topic(),
            new StringEntity(
                getRecordAsJson(record),
                ContentType.create(KAFKA_V2_JSON, StandardCharsets.UTF_8)));
    return Futures.transformAsync(
        JdkFutureAdapters.listenInPoolThread(httpclient.execute(post, null), futureExecutor),
        this::getRecordMetadataResult,
        futureExecutor);
  }

  private HttpPost createPostToTopic(String topic, HttpEntity postBodyEntity) {
    HttpPost post =
        new HttpPost(
            kafkaRestApi.resolve("/topics/" + URLEncoder.encode(topic, StandardCharsets.UTF_8)));
    post.addHeader(HttpHeaders.ACCEPT, "*/*");
    post.setConfig(httpRequestConf);
    post.setEntity(postBodyEntity);
    return post;
  }

  @Override
  public void flush() {
    unsupported();
  }

  @Override
  public List<PartitionInfo> partitionsFor(String topic) {
    return unsupported();
  }

  @Override
  public Map<MetricName, ? extends Metric> metrics() {
    return unsupported();
  }

  @Override
  public void close() {
    try {
      httpclient.close();
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Unable to close httpclient");
    }
  }

  @Override
  public void close(long timeout, TimeUnit unit) {
    close();
  }

  private ListenableFuture<RecordMetadata> getRecordMetadataResult(HttpResponse response) {
    switch (response.getStatusLine().getStatusCode()) {
      case HttpStatus.SC_OK:
        return Futures.immediateFuture(ZEROS_RECORD_METADATA);
      default:
        return Futures.immediateFailedFuture(
            new IOException("Request failed: " + response.getStatusLine()));
    }
  }

  private String getRecordAsJson(ProducerRecord<String, String> record) {
    return String.format(
        "{\"records\":[{\"key\":\"%s\",\"value\":%s}]}", record.key(), record.value());
  }

  private <T> T unsupported() {
    throw new IllegalArgumentException("Unsupported method");
  }
}
