// Copyright (C) 2025 The Android Open Source Project
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

import com.google.gerrit.server.events.Event;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.kafka.config.KafkaPublisherProperties;
import java.util.Set;

/**
 * Filters events based on configured event types.
 *
 * <p>When event types are configured, only events matching those types are allowed. When no event
 * types are configured, all events are allowed (default behavior).
 */
@Singleton
public class EventTypeFilter {

  private final Set<String> allowedEventTypes;

  /**
   * Constructs an EventTypeFilter with the configured event types.
   *
   * @param properties the Kafka publisher properties containing event type configuration
   */
  @Inject
  public EventTypeFilter(KafkaPublisherProperties properties) {
    this.allowedEventTypes = properties.getAllowedEventTypes();
  }

  /**
   * Determines if an event should be published based on its type.
   *
   * @param event the event to check
   * @return true if the event should be published, false otherwise
   */
  public boolean shouldPublish(Event event) {
    // If no event types configured, publish all events
    if (allowedEventTypes.isEmpty()) {
      return true;
    }

    // Check if the event type is in the allowed list
    String eventType = event.getType();
    return allowedEventTypes.contains(eventType);
  }
}
