Event Type Filtering
====================

This plugin supports selective event publishing similar to the `gerrit stream-events -s` flag.
By default, all Gerrit events are published to Kafka. You can configure the plugin to publish
only specific event types.

Configuration
-------------

To filter events, add `eventType` configuration to `gerrit.config`:

```
[plugin "@PLUGIN@"]
    bootstrapServers = localhost:9092
    eventType = patchset-created
    eventType = comment-added
    eventType = change-merged
```

The `eventType` option can be specified multiple times to publish multiple event types.

Behavior
--------

- **When no `eventType` is specified:** All events are published (default behavior, backward compatible)
- **When one or more `eventType` values are specified:** Only events matching the configured types are published

Common Event Types
------------------

The following event types are commonly used:

| Event Type          | Description                          |
|:--------------------|:-------------------------------------|
| `patchset-created`  | New patchset uploaded                |
| `comment-added`     | Review comment posted                |
| `change-merged`     | Change merged                        |
| `change-abandoned`  | Change abandoned                     |
| `ref-updated`       | Git reference updated                |
| `reviewer-added`    | Reviewer added to change             |
| `draft-published`   | Draft change published               |
| `merge-failed`      | Merge attempt failed                 |
| `change-restored`   | Abandoned change restored            |
| `project-created`   | New project created                  |

For a complete list of event types, see the Gerrit documentation for `stream-events`.

Examples
--------

### Publish only code review events

```
[plugin "@PLUGIN@"]
    bootstrapServers = localhost:9092
    eventType = patchset-created
    eventType = comment-added
    eventType = change-merged
```

### Publish only ref updates

```
[plugin "@PLUGIN@"]
    bootstrapServers = localhost:9092
    eventType = ref-updated
```

### Publish all events (default)

```
[plugin "@PLUGIN@"]
    bootstrapServers = localhost:9092
    # No eventType specified - all events are published
```

Implementation Details
----------------------

Event type filtering is implemented in the following classes:

- `EventTypeFilter` - Core filtering logic that checks if events should be published
- `KafkaPublisherProperties` - Reads and stores `eventType` configuration from `gerrit.config`
- `KafkaPublisher` - Integrates the filter before publishing events to Kafka

The event type matching is **case-sensitive** and uses the value returned by `Event.getType()`.

Performance Considerations
--------------------------

Event filtering happens **before** serialization and network transmission, which means:

- Filtered events are not serialized to JSON
- Filtered events do not consume Kafka producer resources
- No network bandwidth is used for filtered events

This makes event filtering efficient for high-volume Gerrit installations where you only
need to track specific event types.
