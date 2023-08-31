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

package com.googlesource.gerrit.plugins.kafka.api;

import com.gerritforge.gerrit.eventbroker.BrokerApi;
import com.gerritforge.gerrit.eventbroker.TopicSubscriber;
import com.google.common.collect.Sets;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.googlesource.gerrit.plugins.kafka.broker.ConsumerExecutor;
import com.googlesource.gerrit.plugins.kafka.config.KafkaProperties.ClientType;
import com.googlesource.gerrit.plugins.kafka.config.KafkaSubscriberProperties;
import com.googlesource.gerrit.plugins.kafka.subscribe.*;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Deserializer;

@Singleton
public class KafkaApiModule extends LifecycleModule {
  private Set<TopicSubscriber> activeConsumers = Sets.newHashSet();
  private WorkQueue workQueue;
  private KafkaSubscriberProperties configuration;

  @Inject
  public KafkaApiModule(WorkQueue workQueue, KafkaSubscriberProperties configuration) {
    this.workQueue = workQueue;
    this.configuration = configuration;
  }

  @Inject(optional = true)
  public void setPreviousBrokerApi(DynamicItem<BrokerApi> previousBrokerApi) {
    if (previousBrokerApi != null && previousBrokerApi.get() != null) {
      this.activeConsumers = previousBrokerApi.get().topicSubscribers();
    }
  }

  @Override
  protected void configure() {
    ClientType clientType = configuration.getClientType();
    switch (clientType) {
      case NATIVE:
        install(
            new FactoryModuleBuilder()
                .implement(KafkaEventSubscriber.class, KafkaEventNativeSubscriber.class)
                .build(KafkaEventSubscriberFactory.class));
        break;
      case REST:
        install(
            new FactoryModuleBuilder()
                .implement(KafkaEventSubscriber.class, KafkaEventRestSubscriber.class)
                .build(KafkaEventSubscriberFactory.class));
        break;
      default:
        throw new IllegalArgumentException("Unsupported Kafka client type " + clientType);
    }

    bind(ExecutorService.class)
        .annotatedWith(ConsumerExecutor.class)
        .toInstance(
            workQueue.createQueue(configuration.getNumberOfSubscribers(), "kafka-subscriber"));

    bind(new TypeLiteral<Deserializer<byte[]>>() {}).toInstance(new ByteArrayDeserializer());
    bind(new TypeLiteral<Deserializer<Event>>() {}).to(KafkaEventDeserializer.class);
    bind(new TypeLiteral<Set<TopicSubscriber>>() {}).toInstance(activeConsumers);

    DynamicItem.bind(binder(), BrokerApi.class).to(KafkaBrokerApi.class).in(Scopes.SINGLETON);
  }
}
