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

package com.googlesource.gerrit.plugins.kafka.publish;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventGson;
import com.google.gerrit.server.events.EventListener;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.kafka.session.KafkaSession;

@Singleton
public class KafkaPublisher implements EventListener {

  private final KafkaSession session;
  private final Gson gson;

  @Inject
  public KafkaPublisher(KafkaSession kafkaSession, @EventGson Gson gson) {
    this.session = kafkaSession;
    this.gson = gson;
  }

  public void start() {
    if (!session.isOpen()) {
      session.connect();
    }
  }

  public void stop() {
    session.disconnect();
  }

  @Override
  public void onEvent(Event event) {
    if (session.isOpen()) {
      session.publish(gson.toJson(event));
    }
  }

  public ListenableFuture<Boolean> publish(String topic, Event event) {
    return session.publish(topic, getPayload(event));
  }

  private String getPayload(Event event) {
    return gson.toJson(event);
  }

  @VisibleForTesting
  public JsonObject eventToJson(Event event) {
    return gson.toJsonTree(event).getAsJsonObject();
  }
}
