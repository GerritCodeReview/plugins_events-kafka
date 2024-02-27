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
import com.gerritforge.gerrit.eventbroker.TopicSubscriberWithGroupId;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.server.events.Event;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaPublisher;
import com.googlesource.gerrit.plugins.kafka.subscribe.KafkaEventSubscriber;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class KafkaBrokerApi implements BrokerApi {

  private final KafkaPublisher publisher;
  private final KafkaEventSubscriber.Factory kafkaEventSubscriberFactory;
  private List<KafkaEventSubscriber> subscribers;

  @Inject
  public KafkaBrokerApi(
      KafkaPublisher publisher, KafkaEventSubscriber.Factory kafkaEventSubscriberFactory) {
    this.publisher = publisher;
    this.kafkaEventSubscriberFactory = kafkaEventSubscriberFactory;
    subscribers = Collections.synchronizedList(new ArrayList<>());
  }

  @Override
  public ListenableFuture<Boolean> send(String topic, Event event) {
    return publisher.publish(topic, event);
  }

  @Override
  public void receiveAsync(String topic, Consumer<Event> eventConsumer) {
    receiveAsync(topic, eventConsumer, Optional.empty());
  }

  @Override
  public void receiveAsync(String topic, String groupId, Consumer<Event> eventConsumer) {
    receiveAsync(topic, eventConsumer, Optional.ofNullable(groupId));
  }

  @Override
  public void disconnect() {
    for (KafkaEventSubscriber subscriber : subscribers) {
      subscriber.shutdown();
    }
    subscribers.clear();
  }

  @Override
  public void disconnect(String topic, @Nullable String groupId) {
    Set<KafkaEventSubscriber> subscribersToDisconnect =
        subscribers.stream()
            .filter(s -> topic.equals(s.getTopic()))
            .filter(
                s -> groupId == null || s.getExternalGroupId().stream().anyMatch(groupId::equals))
            .collect(Collectors.toSet());
    subscribersToDisconnect.forEach(KafkaEventSubscriber::shutdown);
    subscribers.removeAll(subscribersToDisconnect);
  }

  @Override
  public Set<TopicSubscriber> topicSubscribers() {
    return subscribers.stream()
        .filter(s -> !s.getExternalGroupId().isPresent())
        .map(s -> TopicSubscriber.topicSubscriber(s.getTopic(), s.getMessageProcessor()))
        .collect(Collectors.toSet());
  }

  @Override
  public Set<TopicSubscriberWithGroupId> topicSubscribersWithGroupId() {
    return subscribers.stream()
        .filter(s -> s.getExternalGroupId().isPresent())
        .map(
            s ->
                TopicSubscriberWithGroupId.topicSubscriberWithGroupId(
                    s.getExternalGroupId().get(),
                    TopicSubscriber.topicSubscriber(s.getTopic(), s.getMessageProcessor())))
        .collect(Collectors.toSet());
  }

  @Override
  public void replayAllEvents(String topic) {
    subscribers.stream()
        .filter(subscriber -> topic.equals(subscriber.getTopic()))
        .forEach(subscriber -> subscriber.resetOffset());
  }

  private void receiveAsync(
      String topic, Consumer<Event> eventConsumer, Optional<String> externalGroupId) {
    KafkaEventSubscriber subscriber = kafkaEventSubscriberFactory.create(externalGroupId);
    synchronized (subscribers) {
      subscribers.add(subscriber);
    }
    subscriber.subscribe(topic, eventConsumer);
  }
}
