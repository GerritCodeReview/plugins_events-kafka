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

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.events.EventListener;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.TypeLiteral;
import com.googlesource.gerrit.plugins.kafka.api.KafkaApiModule;
import com.googlesource.gerrit.plugins.kafka.config.KafkaPublisherProperties;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaPublisher;
import com.googlesource.gerrit.plugins.kafka.session.KafkaProducerProvider;
import org.apache.kafka.clients.producer.KafkaProducer;

class Module extends AbstractModule {

  private final KafkaApiModule kafkaBrokerModule;
  private final KafkaPublisherProperties configuration;

  @Inject
  public Module(KafkaApiModule kafkaBrokerModule, KafkaPublisherProperties configuration) {
    this.kafkaBrokerModule = kafkaBrokerModule;
    this.configuration = configuration;
  }

  @Override
  protected void configure() {
    DynamicSet.bind(binder(), LifecycleListener.class).to(Manager.class);

    if (configuration.isSendStreamEvents()) {
      DynamicSet.bind(binder(), EventListener.class).to(KafkaPublisher.class);
    }

    bind(new TypeLiteral<KafkaProducer<String, String>>() {})
        .toProvider(KafkaProducerProvider.class);

    install(kafkaBrokerModule);
  }
}
