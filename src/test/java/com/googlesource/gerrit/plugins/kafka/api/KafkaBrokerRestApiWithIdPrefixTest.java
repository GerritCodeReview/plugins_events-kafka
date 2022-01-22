// Copyright (C) 2022 The Android Open Source Project
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

import com.google.inject.TypeLiteral;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties.ClientType;
import com.googlesource.gerrit.plugins.kafka.config.KafkaSubscriberProperties;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaRestProducer;
import com.googlesource.gerrit.plugins.kafka.rest.FutureExecutor;
import com.googlesource.gerrit.plugins.kafka.rest.HttpHostProxy;
import com.googlesource.gerrit.plugins.kafka.rest.KafkaRestClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.producer.Producer;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KafkaBrokerRestApiWithIdPrefixTest extends KafkaBrokerApiTest {

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
                getApiUriString());
        bind(KafkaSubscriberProperties.class).toInstance(kafkaSubscriberProperties);

        bind(HttpHostProxy.class).toInstance(new HttpHostProxy(null, null, null));

        install(new FactoryModuleBuilder().build(KafkaRestClient.Factory.class));
      }
    };
  }

  @Override
  protected String getKafkaRestApiUriString() {
    return getApiUriString();
  }

  private static String getApiUriString() {
    return String.format(
        "http://%s:%d/%s",
        nginx.getHost(),
        nginx.getLivenessCheckPortNumbers().iterator().next(),
        KafkaProperties.REST_API_URI_ID_PLACEHOLDER);
  }
}
