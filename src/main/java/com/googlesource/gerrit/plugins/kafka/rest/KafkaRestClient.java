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

package com.googlesource.gerrit.plugins.kafka.rest;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Function;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gerrit.common.Nullable;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.config.RequestConfig.Builder;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class KafkaRestClient {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String KAFKA_V2_JSON = "application/vnd.kafka.json.v2+json";
  private static final String KAFKA_V2 = "application/vnd.kafka.v2+json";

  private final HttpHostProxy proxy;
  private final CloseableHttpAsyncClient httpclient;
  private final URI kafkaRestApiUri;
  private final ExecutorService futureExecutor;

  public interface Factory {
    KafkaRestClient create(KafkaProperties configuration);
  }

  @Inject
  public KafkaRestClient(
      HttpHostProxy httpHostProxy,
      @FutureExecutor ExecutorService executor,
      @Assisted KafkaProperties configuration) {
    proxy = httpHostProxy;
    httpclient = proxy.apply(HttpAsyncClients.custom()).build();
    kafkaRestApiUri = configuration.getRestApiUri();
    if (configuration.isHttpWireLog()) {
      enableHttpWireLog();
    }
    this.futureExecutor = executor;
  }

  public static void enableHttpWireLog() {
    Logger.getLogger("org.apache.http.wire").setLevel(Level.TRACE);
  }

  public ListenableFuture<HttpResponse> execute(HttpRequestBase request, int... expectedStatuses) {
    return Futures.transformAsync(
        listenableFutureOf(httpclient.execute(request, null)),
        (res) -> {
          IOException exc =
              getResponseException(
                  String.format("HTTP %s %s FAILED", request.getMethod(), request.getURI()),
                  res,
                  expectedStatuses);
          if (exc == null) {
            return Futures.immediateFuture(res);
          }
          return Futures.immediateFailedFuture(exc);
        },
        futureExecutor);
  }

  public <I, O> ListenableFuture<O> mapAsync(
      ListenableFuture<I> inputFuture, AsyncFunction<? super I, ? extends O> mapFunction) {
    return Futures.transformAsync(inputFuture, mapFunction, futureExecutor);
  }

  public <I, O> ListenableFuture<O> map(
      ListenableFuture<I> inputFuture, Function<? super I, ? extends O> mapFunction) {
    return Futures.transform(inputFuture, mapFunction, futureExecutor);
  }

  public HttpGet createGetTopic(String topic) {
    HttpGet get = new HttpGet(kafkaRestApiUri.resolve("/topics/" + topic));
    get.addHeader(HttpHeaders.ACCEPT, KAFKA_V2_JSON);
    get.setConfig(createRequestConfig());
    return get;
  }

  public HttpGet createGetRecords(URI consumerUri) {
    HttpGet get = new HttpGet(consumerUri.resolve(consumerUri.getPath() + "/records"));
    get.addHeader(HttpHeaders.ACCEPT, KAFKA_V2_JSON);
    get.setConfig(createRequestConfig());
    return get;
  }

  public HttpPost createPostToConsumer(String consumerGroup) {
    start();
    HttpPost post =
        new HttpPost(
            kafkaRestApiUri.resolve(
                kafkaRestApiUri.getPath()
                    + "/consumers/"
                    + URLEncoder.encode(consumerGroup, UTF_8)));
    post.addHeader(HttpHeaders.ACCEPT, "*/*");
    post.setConfig(createRequestConfig());
    post.setEntity(
        new StringEntity(
            "{\"format\": \"json\",\"auto.offset.reset\": \"earliest\"}",
            ContentType.create(KAFKA_V2, UTF_8)));
    return post;
  }

  public HttpDelete createDeleteToConsumer(URI consumerUri) {
    start();
    HttpDelete delete = new HttpDelete(consumerUri);
    delete.addHeader(HttpHeaders.ACCEPT, "*/*");
    delete.setConfig(createRequestConfig());
    return delete;
  }

  public HttpPost createPostToSubscribe(URI consumerUri, String topic) {
    start();
    HttpPost post = new HttpPost(consumerUri.resolve(consumerUri.getPath() + "/subscription"));
    post.addHeader(HttpHeaders.ACCEPT, "*/*");
    post.setConfig(createRequestConfig());
    post.setEntity(
        new StringEntity(
            String.format("{\"topics\":[\"%s\"]}", topic), ContentType.create(KAFKA_V2, UTF_8)));
    return post;
  }

  public HttpPost createPostToTopic(String topic, HttpEntity postBodyEntity) {
    start();
    HttpPost post =
        new HttpPost(kafkaRestApiUri.resolve("/topics/" + URLEncoder.encode(topic, UTF_8)));
    post.addHeader(HttpHeaders.ACCEPT, "*/*");
    post.setConfig(createRequestConfig());
    post.setEntity(postBodyEntity);
    return post;
  }

  public HttpPost createPostSeekTopicToBeginning(
      URI consumerInstanceURI, String topic, Set<Integer> partitions) {
    start();
    HttpPost post = new HttpPost(consumerInstanceURI.resolve("/positions/beginning"));
    post.addHeader(HttpHeaders.ACCEPT, "*/*");
    post.setConfig(createRequestConfig());
    post.setEntity(
        new StringEntity(
            String.format(
                "{\"partitions\",[%s]}",
                partitions.stream()
                    .map(
                        partition ->
                            String.format("{\"topic\":\"%s\",\"partition\":%d}", topic, partition))
                    .collect(Collectors.joining(","))),
            UTF_8));
    return post;
  }

  @Nullable
  public IOException getResponseException(
      String errorMessage, HttpResponse response, int... okHttpStatuses) {
    int responseHttpStatus = response.getStatusLine().getStatusCode();
    if (okHttpStatuses.length == 0) {
      okHttpStatuses =
          new int[] {HttpStatus.SC_OK, HttpStatus.SC_CREATED, HttpStatus.SC_NO_CONTENT};
    }
    for (int httpStatus : okHttpStatuses) {
      if (responseHttpStatus == httpStatus) {
        return null;
      }
    }

    String responseBody = "";
    try {
      responseBody = getStringEntity(response);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Unable to extrace the string entity for response %d (%s)",
          response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
    }

    return new IOException(
        String.format(
            "%s\nHTTP status %d (%s)\n%s",
            errorMessage,
            response.getStatusLine().getStatusCode(),
            response.getStatusLine().getReasonPhrase(),
            responseBody));
  }

  protected String getStringEntity(HttpResponse response) throws IOException {
    HttpEntity entity = response.getEntity();
    try (ByteArrayOutputStream outStream = new ByteArrayOutputStream()) {
      entity.writeTo(outStream);
      outStream.close();
      return outStream.toString(UTF_8);
    }
  }

  private <V> ListenableFuture<V> listenableFutureOf(Future<V> future) {
    return JdkFutureAdapters.listenInPoolThread(future, futureExecutor);
  }

  private RequestConfig createRequestConfig() {
    Builder configBuilder = RequestConfig.custom();
    configBuilder = proxy.apply(configBuilder);
    RequestConfig config = configBuilder.build();
    return config;
  }

  public void close() throws IOException {
    httpclient.close();
  }

  private void start() {
    httpclient.start();
  }

  public URI resolveURI(String path) {
    return kafkaRestApiUri.resolve(path);
  }
}
