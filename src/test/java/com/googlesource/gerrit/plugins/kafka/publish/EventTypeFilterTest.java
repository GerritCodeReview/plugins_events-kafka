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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.server.events.Event;
import com.googlesource.gerrit.plugins.kafka.config.KafkaPublisherProperties;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for EventTypeFilter.
 */
public class EventTypeFilterTest {

  private KafkaPublisherProperties properties;
  private Event mockEvent;

  @Before
  public void setUp() {
    properties = mock(KafkaPublisherProperties.class);
    mockEvent = mock(Event.class);
  }

  @Test
  public void shouldPublishAllEventsWhenNoFilterConfigured() {
    when(properties.getAllowedEventTypes()).thenReturn(Collections.emptySet());
    EventTypeFilter filter = new EventTypeFilter(properties);

    when(mockEvent.getType()).thenReturn("patchset-created");
    assertThat(filter.shouldPublish(mockEvent)).isTrue();

    when(mockEvent.getType()).thenReturn("comment-added");
    assertThat(filter.shouldPublish(mockEvent)).isTrue();

    when(mockEvent.getType()).thenReturn("change-merged");
    assertThat(filter.shouldPublish(mockEvent)).isTrue();
  }

  @Test
  public void shouldPublishOnlyConfiguredEventTypes() {
    when(properties.getAllowedEventTypes())
        .thenReturn(ImmutableSet.of("patchset-created", "comment-added"));
    EventTypeFilter filter = new EventTypeFilter(properties);

    when(mockEvent.getType()).thenReturn("patchset-created");
    assertThat(filter.shouldPublish(mockEvent)).isTrue();

    when(mockEvent.getType()).thenReturn("comment-added");
    assertThat(filter.shouldPublish(mockEvent)).isTrue();

    when(mockEvent.getType()).thenReturn("change-merged");
    assertThat(filter.shouldPublish(mockEvent)).isFalse();

    when(mockEvent.getType()).thenReturn("change-abandoned");
    assertThat(filter.shouldPublish(mockEvent)).isFalse();
  }

  @Test
  public void shouldFilterSingleEventType() {
    when(properties.getAllowedEventTypes()).thenReturn(ImmutableSet.of("patchset-created"));
    EventTypeFilter filter = new EventTypeFilter(properties);

    when(mockEvent.getType()).thenReturn("patchset-created");
    assertThat(filter.shouldPublish(mockEvent)).isTrue();

    when(mockEvent.getType()).thenReturn("comment-added");
    assertThat(filter.shouldPublish(mockEvent)).isFalse();
  }

  @Test
  public void shouldHandleCaseSensitiveEventTypes() {
    when(properties.getAllowedEventTypes()).thenReturn(ImmutableSet.of("patchset-created"));
    EventTypeFilter filter = new EventTypeFilter(properties);

    when(mockEvent.getType()).thenReturn("patchset-created");
    assertThat(filter.shouldPublish(mockEvent)).isTrue();

    when(mockEvent.getType()).thenReturn("Patchset-Created");
    assertThat(filter.shouldPublish(mockEvent)).isFalse();
  }
}
