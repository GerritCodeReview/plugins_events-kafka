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

import com.gerritforge.gerrit.eventbroker.BrokerApi;
import com.gerritforge.gerrit.eventbroker.ExtendedBrokerApi;
import com.gerritforge.gerrit.eventbroker.TopicSubscriber;
import com.gerritforge.gerrit.eventbroker.TopicSubscriberWithGroupId;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaPublisher;
import java.util.Set;

@Singleton
public class Manager implements LifecycleListener {

  private final KafkaPublisher publisher;
  private final Set<TopicSubscriber> consumers;
  private final Set<TopicSubscriberWithGroupId> consumersWithGroupId;
  private final ExtendedBrokerApi brokerApi;

  @Inject
  public Manager(
      KafkaPublisher publisher,
      Set<TopicSubscriber> consumers,
      Set<TopicSubscriberWithGroupId> consumersWithGroupId,
      BrokerApi brokerApi) {
    this.publisher = publisher;
    this.consumers = consumers;
    this.brokerApi = (ExtendedBrokerApi) brokerApi;
    this.consumersWithGroupId = consumersWithGroupId;
  }

  @Override
  public void start() {
    publisher.start();
    consumers.forEach(
        topicSubscriber ->
            brokerApi.receiveAsync(topicSubscriber.topic(), topicSubscriber.consumer()));

    consumersWithGroupId.forEach(
        topicSubscriber ->
            brokerApi.receiveAsync(
                topicSubscriber.topicSubscriber().topic(),
                topicSubscriber.groupId(),
                topicSubscriber.topicSubscriber().consumer()));
  }

  @Override
  public void stop() {
    publisher.stop();
    brokerApi.disconnect();
  }
}
