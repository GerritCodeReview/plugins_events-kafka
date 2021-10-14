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

package com.googlesource.gerrit.plugins.kafka.subscribe;

import com.gerritforge.gerrit.eventbroker.EventMessage;

/** Generic interface to a Kafka topic subscriber. */
public interface KafkaEventSubscriber {

  /**
   * Subscribe to a topic and receive messages asynchronously.
   *
   * @param topic Kafka topic name
   * @param messageProcessor consumer function for processing incoming messages
   */
  void subscribe(String topic, java.util.function.Consumer<EventMessage> messageProcessor);

  /** Shutdown Kafka consumer. */
  void shutdown();

  /**
   * Returns the current consumer function for the subscribed topic.
   *
   * @return the default topic consumer function.
   */
  java.util.function.Consumer<EventMessage> getMessageProcessor();

  /**
   * Returns the current subscribed topic name.
   *
   * @return Kafka topic name.
   */
  String getTopic();

  /** Reset the offset for reading incoming Kafka messages of the topic. */
  void resetOffset();
}
