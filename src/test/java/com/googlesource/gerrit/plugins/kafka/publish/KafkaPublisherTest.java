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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.events.Event;
import com.google.gson.Gson;
import com.googlesource.gerrit.plugins.kafka.session.KafkaSession;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for KafkaPublisher with event filtering.
 */
@RunWith(MockitoJUnitRunner.class)
public class KafkaPublisherTest {

  @Mock private KafkaSession session;
  @Mock private Gson gson;
  @Mock private EventTypeFilter eventTypeFilter;
  @Mock private Event event;

  private KafkaPublisher publisher;

  @Before
  public void setUp() {
    publisher = new KafkaPublisher(session, gson, eventTypeFilter);
  }

  @Test
  public void shouldPublishEventWhenSessionOpenAndFilterAllows() {
    when(session.isOpen()).thenReturn(true);
    when(eventTypeFilter.shouldPublish(event)).thenReturn(true);
    when(gson.toJson(event)).thenReturn("{\"type\":\"patchset-created\"}");

    publisher.onEvent(event);

    verify(session).publish("{\"type\":\"patchset-created\"}");
  }

  @Test
  public void shouldNotPublishEventWhenFilterRejects() {
    when(session.isOpen()).thenReturn(true);
    when(eventTypeFilter.shouldPublish(event)).thenReturn(false);

    publisher.onEvent(event);

    verify(session, never()).publish(anyString());
  }

  @Test
  public void shouldNotPublishEventWhenSessionClosed() {
    when(session.isOpen()).thenReturn(false);
    when(eventTypeFilter.shouldPublish(event)).thenReturn(true);

    publisher.onEvent(event);

    verify(session, never()).publish(anyString());
  }

  @Test
  public void shouldNotPublishEventWhenSessionClosedAndFilterRejects() {
    when(session.isOpen()).thenReturn(false);
    when(eventTypeFilter.shouldPublish(event)).thenReturn(false);

    publisher.onEvent(event);

    verify(session, never()).publish(anyString());
  }
}
