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

import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.httpd.ProxyProperties;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.kafka.config.KafkaSubscriberProperties;
import com.googlesource.gerrit.plugins.kafka.publish.FutureExecutor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
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
  private static final String KAFKA_V2_JSON = "application/vnd.kafka.json.v2+json";
  private static final String KAFKA_V2 = "application/vnd.kafka.v2+json";

  private final Optional<HttpHost> proxy;
  private final CloseableHttpAsyncClient httpclient;
  private final URI kafkaRestApiUri;
  private final ExecutorService futureExecutor;

  @Inject
  public KafkaRestClient(
      ProxyProperties proxyConf,
      KafkaSubscriberProperties configuration,
      @FutureExecutor ExecutorService executor) {
    proxy = getProxy(proxyConf);
    httpclient = HttpAsyncClients.createDefault();
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

  public HttpGet createGetRecords(URI consumerUri) {
    HttpGet get = new HttpGet(consumerUri.resolve(consumerUri.getPath() + "/records"));
    get.addHeader(HttpHeaders.ACCEPT, KAFKA_V2_JSON);
    get.setConfig(createRequestConfig());
    return get;
  }

  public HttpPost createPostToConsumer(String consumerGroup) {
    open();
    HttpPost post =
        new HttpPost(
            kafkaRestApiUri.resolve(
                kafkaRestApiUri.getPath()
                    + "/consumers/"
                    + URLEncoder.encode(consumerGroup, StandardCharsets.UTF_8)));
    post.addHeader(HttpHeaders.ACCEPT, "*/*");
    post.setConfig(createRequestConfig());
    post.setEntity(
        new StringEntity(
            "{\"format\": \"json\", \"auto.offset.reset\": \"earliest\"}",
            ContentType.create(KAFKA_V2, StandardCharsets.UTF_8)));
    return post;
  }

  public HttpDelete createDeleteToConsumer(URI consumerUri) {
    open();
    HttpDelete delete = new HttpDelete(consumerUri);
    delete.addHeader(HttpHeaders.ACCEPT, "*/*");
    delete.setConfig(createRequestConfig());
    return delete;
  }

  public HttpPost createPostToSubscribe(URI consumerUri, String topic) {
    open();
    HttpPost post = new HttpPost(consumerUri.resolve(consumerUri.getPath() + "/subscription"));
    post.addHeader(HttpHeaders.ACCEPT, "*/*");
    post.setConfig(createRequestConfig());
    post.setEntity(
        new StringEntity(
            String.format("{\"topics\": [\"%s\"]}", topic),
            ContentType.create(KAFKA_V2, StandardCharsets.UTF_8)));
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
      e.printStackTrace();
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
      return outStream.toString("UTF-8");
    }
  }

  private <V> ListenableFuture<V> listenableFutureOf(Future<V> future) {
    return JdkFutureAdapters.listenInPoolThread(future, futureExecutor);
  }

  private RequestConfig createRequestConfig() {
    Builder configBuilder = RequestConfig.custom();
    configBuilder = proxy.map(configBuilder::setProxy).orElse(configBuilder);
    RequestConfig config = configBuilder.build();
    return config;
  }

  private static Optional<HttpHost> getProxy(ProxyProperties proxyConf) {
    return Optional.ofNullable(proxyConf.getProxyUrl())
        .map((url) -> url.toString())
        .map(HttpHost::create);
  }

  public void close() throws IOException {
    httpclient.close();
  }

  private void open() {
    httpclient.start();
  }

  public URI resolveURI(String path) {
    return kafkaRestApiUri.resolve(path);
  }
}
