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

package com.googlesource.gerrit.plugins.kafka.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Arrays;
import java.util.Set;

/**
 * Configuration properties for Kafka event publishing.
 *
 * <p>Extends base Kafka properties to add event filtering support.
 */
@Singleton
public class KafkaPublisherProperties extends KafkaProperties {
  private static final long serialVersionUID = 0L;
  private static final String EVENT_TYPE_CONFIG = "eventType";

  private final Set<String> allowedEventTypes;

  /**
   * Constructs publisher properties from Gerrit configuration.
   *
   * @param configFactory factory for reading plugin configuration
   * @param pluginName the name of this plugin
   */
  @Inject
  public KafkaPublisherProperties(
      PluginConfigFactory configFactory, @PluginName String pluginName) {
    super(configFactory, pluginName);

    PluginConfig config = configFactory.getFromGerritConfig(pluginName);
    String[] eventTypes = config.getStringList(EVENT_TYPE_CONFIG);
    this.allowedEventTypes = ImmutableSet.copyOf(Arrays.asList(eventTypes));
  }

  /**
   * Constructor for testing.
   *
   * @param sendAsync whether to send messages asynchronously
   * @param allowedEventTypes set of event types to publish
   */
  @VisibleForTesting
  public KafkaPublisherProperties(boolean sendAsync, Set<String> allowedEventTypes) {
    super(sendAsync);
    this.allowedEventTypes = ImmutableSet.copyOf(allowedEventTypes);
  }

  /**
   * Gets the configured event types to publish.
   *
   * @return set of event types to publish; empty set means publish all events
   */
  public Set<String> getAllowedEventTypes() {
    return allowedEventTypes;
  }
}
