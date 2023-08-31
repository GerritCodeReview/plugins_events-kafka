package com.googlesource.gerrit.plugins.kafka.subscribe;

import java.util.Optional;

public interface KafkaEventSubscriberFactory {
  KafkaEventSubscriber create(Optional<String> externalGroupId);
}
