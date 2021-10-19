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

package com.googlesource.gerrit.plugins.kafka.api;

import com.google.gerrit.httpd.ProxyProperties;
import com.google.inject.TypeLiteral;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties.ClientType;
import com.googlesource.gerrit.plugins.kafka.config.KafkaSubscriberProperties;
import com.googlesource.gerrit.plugins.kafka.publish.FutureExecutor;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaRestProducer;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.producer.Producer;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KafkaBrokerRestApiTest extends KafkaBrokerApiTest {

  @Override
  @Before
  public void setup() {
    clientType = ClientType.REST;
  }

  @Override
  protected TestModule newTestModule(KafkaProperties kafkaProperties) {
    return new TestModule(kafkaProperties) {

      @Override
      protected void bindKafkaClientImpl() {
        bind(new TypeLiteral<Producer<String, String>>() {}).to(KafkaRestProducer.class);
        bind(ExecutorService.class)
            .annotatedWith(FutureExecutor.class)
            .toInstance(Executors.newCachedThreadPool());

        KafkaSubscriberProperties kafkaSubscriberProperties =
            new KafkaSubscriberProperties(
                TEST_POLLING_INTERVAL_MSEC,
                TEST_GROUP_ID,
                TEST_NUM_SUBSCRIBERS,
                ClientType.REST,
                kafkaRest.getApiURI());
        bind(KafkaSubscriberProperties.class).toInstance(kafkaSubscriberProperties);

        bind(ProxyProperties.class)
            .toInstance(
                new ProxyProperties() {

                  @Override
                  public URL getProxyUrl() {
                    return null;
                  }

                  @Override
                  public String getUsername() {
                    return null;
                  }

                  @Override
                  public String getPassword() {
                    return null;
                  }
                });
      }
    };
  }

  protected URI getKafkaRestApiURI() {
    return kafkaRest.getApiURI();
  }
}
