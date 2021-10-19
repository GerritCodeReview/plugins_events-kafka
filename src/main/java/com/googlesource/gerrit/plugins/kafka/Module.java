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

package com.googlesource.gerrit.plugins.kafka;

import com.gerritforge.gerrit.eventbroker.EventGsonProvider;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.events.EventListener;
import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.googlesource.gerrit.plugins.kafka.api.KafkaApiModule;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties.ClientType;
import com.googlesource.gerrit.plugins.kafka.config.RequestConfigProvider;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaPublisher;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaRestProducer;
import com.googlesource.gerrit.plugins.kafka.session.KafkaProducerProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.kafka.clients.producer.Producer;

class Module extends AbstractModule {

  private final KafkaApiModule kafkaBrokerModule;
  private final KafkaProperties kafkaConf;

  @Inject
  public Module(KafkaApiModule kafkaBrokerModule, KafkaProperties kafkaConf) {
    this.kafkaBrokerModule = kafkaBrokerModule;
    this.kafkaConf = kafkaConf;
  }

  @Override
  protected void configure() {
    bind(Gson.class).toProvider(EventGsonProvider.class).in(Singleton.class);
    DynamicSet.bind(binder(), LifecycleListener.class).to(Manager.class);
    DynamicSet.bind(binder(), EventListener.class).to(KafkaPublisher.class);

    ClientType clientType = kafkaConf.getClientType();
    switch (clientType) {
      case NATIVE:
        bind(new TypeLiteral<Producer<String, String>>() {})
            .toProvider(KafkaProducerProvider.class);
        break;
      case REST:
        bind(RequestConfig.class).toProvider(RequestConfigProvider.class).in(Scopes.SINGLETON);
        bind(new TypeLiteral<Producer<String, String>>() {}).to(KafkaRestProducer.class);
        break;
      default:
        throw new IllegalArgumentException("Unsupport Kafka client type " + clientType);
    }

    install(kafkaBrokerModule);
  }
}
