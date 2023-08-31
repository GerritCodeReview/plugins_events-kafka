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

import com.gerritforge.gerrit.eventbroker.ExtendedBrokerApi;
import com.gerritforge.gerrit.eventbroker.TopicSubscriber;
import com.gerritforge.gerrit.eventbroker.TopicSubscriberWithGroupId;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gerrit.server.events.Event;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.kafka.publish.KafkaPublisher;
import com.googlesource.gerrit.plugins.kafka.subscribe.KafkaEventSubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class KafkaBrokerApi implements ExtendedBrokerApi {

  private final KafkaPublisher publisher;
  private final Provider<KafkaEventSubscriber> subscriberProvider;
  private List<KafkaEventSubscriber> subscribers;
  private List<KafkaEventSubscriber> subscribersWithGroupId;

  @Inject
  public KafkaBrokerApi(
      KafkaPublisher publisher, Provider<KafkaEventSubscriber> subscriberProvider) {
    this.publisher = publisher;
    this.subscriberProvider = subscriberProvider;
    subscribers = new ArrayList<>();
    this.subscribersWithGroupId = new ArrayList<>();
  }

  @Override
  public ListenableFuture<Boolean> send(String topic, Event event) {
    return publisher.publish(topic, event);
  }

  @Override
  public void receiveAsync(String topic, Consumer<Event> eventConsumer) {
    KafkaEventSubscriber subscriber = subscriberProvider.get();
    synchronized (subscribers) {
      subscribers.add(subscriber);
    }
    subscriber.subscribe(topic, eventConsumer);
  }

  @Override
  public void receiveAsync(String topic, String groupId, Consumer<Event> eventConsumer) {
    KafkaEventSubscriber subscriber = subscriberProvider.get();
    synchronized (subscribersWithGroupId) {
      subscribersWithGroupId.add(subscriber);
    }
    subscriber.subscribe(topic, groupId, eventConsumer);
  }

  @Override
  public void disconnect() {
    List<KafkaEventSubscriber> allSubscribers = allSubscribers(subscribers, subscribersWithGroupId);
    for (KafkaEventSubscriber subscriber : allSubscribers) {
      subscriber.shutdown();
    }
    allSubscribers.clear();
  }

  @Override
  public Set<TopicSubscriber> topicSubscribers() {
    return subscribers.stream()
        .map(s -> TopicSubscriber.topicSubscriber(s.getTopic(), s.getMessageProcessor()))
        .collect(Collectors.toSet());
  }

  @Override
  public Set<TopicSubscriberWithGroupId> topicSubscribersWithGroupId() {
    return subscribersWithGroupId.stream()
        .map(
            s ->
                TopicSubscriberWithGroupId.topicSubscriberWithGroupId(
                    s.getGroupId(),
                    TopicSubscriber.topicSubscriber(s.getTopic(), s.getMessageProcessor())))
        .collect(Collectors.toSet());
  }

  @Override
  public void replayAllEvents(String topic) {
    allSubscribers(subscribers, subscribersWithGroupId).stream()
        .filter(subscriber -> topic.equals(subscriber.getTopic()))
        .forEach(subscriber -> subscriber.resetOffset());
  }

  private List<KafkaEventSubscriber> allSubscribers(
      List<KafkaEventSubscriber> subscribers, List<KafkaEventSubscriber> subscribersWithGroupId) {
    List<KafkaEventSubscriber> allSubscribers = new ArrayList<>();
    allSubscribers.addAll(subscribers);
    allSubscribers.addAll(subscribersWithGroupId);
    return allSubscribers;
  }
}
