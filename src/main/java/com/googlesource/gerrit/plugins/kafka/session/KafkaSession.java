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

package com.googlesource.gerrit.plugins.kafka.session;

/** Generic session of a Kafka topic producer. */
public interface KafkaSession {

  /**
   * Return the current status of the session.
   *
   * @return true if the session is open, false otherwise
   */
  boolean isOpen();

  /** Connect a Kafka producer. */
  void connect();

  /** Disconnect a Kafka producer. */
  void disconnect();

  /**
   * Publish a message to the default topic on Kafka.
   *
   * @param messageBody body of the Kafka message to publish
   */
  void publish(String messageBody);

  /**
   * Public a message to an arbitrary topic on Kafka.
   *
   * @param topic destination of the Kafka message
   * @param messageBody body of the Kafka message to publish
   * @return true if the message has been published, false otherwise
   */
  boolean publish(String topic, String messageBody);
}
