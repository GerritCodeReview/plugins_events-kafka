# Events-Kafka Plugin Architecture

## Overview

The `events-kafka` plugin is a Gerrit Code Review plugin that bridges Gerrit's event system with Apache Kafka. It publishes Gerrit stream events to Kafka topics, enabling distributed event consumption by external systems for CI/CD integration, multi-site replication, metrics collection, and other event-driven workflows.

## Core Functionality

- **Event Publishing**: Captures all Gerrit stream events (patchset-created, change-merged, etc.) and publishes them to Kafka
- **Event Subscription**: Consumes events from Kafka topics for processing within Gerrit
- **Bidirectional Streaming**: Supports both publishing and consuming events
- **Multiple Client Types**: Native Kafka client or Confluent REST API Proxy
- **Flexible Deployment**: Async/sync publishing modes, configurable threading, consumer groups

## Architectural Components

### 1. Event Listener & Publisher

#### KafkaPublisher (`src/main/java/com/googlesource/gerrit/plugins/kafka/publish/KafkaPublisher.java`)

The entry point for capturing Gerrit events.

- **Interface**: Implements Gerrit's `EventListener` interface
- **Registration**: Registered via Guice `DynamicSet` binding in `Module.java:62-64`
- **Event Reception**: `onEvent(Event event)` method (line 51) receives all Gerrit stream events
- **Serialization**: Converts events to JSON using Gerrit's `@EventGson` Gson instance
- **Delegation**: Passes serialized events to `KafkaSession` for publishing

**Key Methods**:
- `onEvent(Event event)` - Receives events from Gerrit core, publishes to default topic
- `publish(String topic, Event event)` - Publishes to specific topic

### 2. Session Management

#### KafkaSession (`src/main/java/com/googlesource/gerrit/plugins/kafka/session/KafkaSession.java`)

Manages the Kafka connection lifecycle and message publishing.

- **Connection Management**:
  - `connect()` (line 64) - Establishes connection, sets classloader context
  - `disconnect()` (line 115) - Closes producer and cleans up
- **Publishing Modes**:
  - **Async** (default, lines 153-178): Non-blocking, uses Kafka callbacks
  - **Sync** (lines 135-151): Blocks until acknowledgment received
- **Message Structure**:
  - **Key**: Current time in nanoseconds (`System.nanoTime()`)
  - **Value**: JSON-serialized Gerrit event

### 3. Producer Implementations

The plugin supports two producer types selected via `clientType` configuration:

#### Native Producer (`src/main/java/com/googlesource/gerrit/plugins/kafka/session/KafkaProducerProvider.java`)

- **Type**: Standard Apache Kafka `KafkaProducer<String, String>`
- **Usage**: Direct TCP connection to Kafka brokers
- **Configuration**: Uses `bootstrapServers` and standard Kafka producer properties
- **Binding**: `Module.java:71-73`

#### REST Producer (`src/main/java/com/googlesource/gerrit/plugins/kafka/publish/KafkaRestProducer.java`)

- **Type**: HTTP client implementation of Kafka `Producer` interface
- **Usage**: Communicates via Confluent REST Proxy API
- **Message Format**: Wraps records in JSON `{"records":[{"key":"...", "value":"..."}]}`
- **Configuration**: Requires `restApiUri`, optional `restApiUsername`/`restApiPassword`
- **Binding**: `Module.java:74-84`

### 4. Subscriber System

#### KafkaEventSubscriber Interface (`src/main/java/com/googlesource/gerrit/plugins/kafka/subscribe/KafkaEventSubscriber.java`)

Defines the contract for consuming events from Kafka topics.

- **Factory Pattern**: `KafkaEventSubscriber.Factory` creates appropriate subscriber implementation
- **Methods**:
  - `subscribe(String topic, Consumer<Event> messageProcessor)` - Starts consumption
  - `disconnect()` - Stops consumption and releases resources

#### Native Subscriber (`src/main/java/com/googlesource/gerrit/plugins/kafka/subscribe/KafkaEventNativeSubscriber.java`)

- **Consumer**: Apache Kafka `KafkaConsumer<String, String>`
- **Threading**: Dedicated background thread per subscriber (ReceiverJob)
- **Polling**: Continuous loop with configurable `pollingIntervalMs`
- **Deserialization**: `KafkaEventDeserializer` converts JSON to Gerrit `Event` objects
- **Flow**:
  1. Subscribe to topic (line 98)
  2. Poll for records (line 177)
  3. Deserialize each record (lines 181-182)
  4. Invoke message processor callback (line 183)

#### REST Subscriber (`src/main/java/com/googlesource/gerrit/plugins/kafka/subscribe/KafkaEventRestSubscriber.java`)

- **Client**: HTTP client for Confluent REST Proxy
- **Initialization**:
  1. HTTP POST to create consumer (line 184)
  2. HTTP POST to subscribe to topic (lines 264-266)
- **Polling**: HTTP GET requests for records (lines 258-261)
- **Threading**: Managed via executor service with `numberOfSubscribers` threads
- **Cleanup**: Deletes consumer instance on shutdown (line 271)

### 5. Configuration Management

#### KafkaProperties (`src/main/java/com/googlesource/gerrit/plugins/kafka/config/KafkaProperties.java`)

Central configuration class extending `java.util.Properties`.

**Default Kafka Producer Settings** (lines 156-165):
```properties
acks=all
retries=0
batch.size=16384
linger.ms=1
buffer.memory=33554432
key.serializer=StringSerializer
value.serializer=StringSerializer
reconnect.backoff.ms=5000
```

#### KafkaPublisherProperties & KafkaSubscriberProperties

Specialized configuration for publisher and subscriber components with topic-specific settings.

### 6. Broker API

#### KafkaBrokerApi (`src/main/java/com/googlesource/gerrit/plugins/kafka/api/KafkaBrokerApi.java`)

Implements the generic Events Broker API for plugin interoperability.

**Interface**: `BrokerApi` from events-broker library

**Methods**:
- `send(String topic, Event event)` - Publish event to specified topic
- `receiveAsync(String topic, Consumer<Event> consumer)` - Subscribe to topic
- `disconnect()` - Cleanup and shutdown

### 7. Lifecycle Management

#### Manager (`src/main/java/com/googlesource/gerrit/plugins/kafka/Manager.java`)

Plugin lifecycle coordinator implementing Gerrit's `LifecycleListener`.

**Responsibilities**:
- **Startup** (`start()`, lines 46-61):
  - Connects publisher session
  - Initializes all registered topic subscribers via `BrokerApi`
- **Shutdown** (`stop()`, lines 63-67):
  - Disconnects publisher
  - Stops broker API and releases resources

#### Module (`src/main/java/com/googlesource/gerrit/plugins/kafka/Module.java`)

Guice dependency injection configuration.

**Bindings** (lines 58-87):
- Registers `KafkaPublisher` as `EventListener` (if `sendStreamEvents=true`)
- Binds appropriate producer implementation based on `clientType`
- Registers `KafkaBrokerApi` as `BrokerApi` implementation
- Binds subscriber factories for native/REST consumers

## Data Flow

### Publishing Flow: Gerrit Event → Kafka

```
┌─────────────────┐
│  Gerrit Core    │
│  (Event Fire)   │
└────────┬────────┘
         │ Event object
         ▼
┌─────────────────────────┐
│  KafkaPublisher         │
│  onEvent(Event)         │
│  - Check session open   │
│  - Serialize to JSON    │
└────────┬────────────────┘
         │ JSON string
         ▼
┌─────────────────────────┐
│  KafkaSession           │
│  publish(String)        │
│  - Create ProducerRecord│
│  - Route async/sync     │
└────────┬────────────────┘
         │ ProducerRecord<String, String>
         │ (key: nanoTime, value: JSON)
         ▼
    ┌────┴────┐
    │         │
    ▼         ▼
┌─────────┐ ┌──────────────┐
│ Native  │ │ REST Proxy   │
│ Kafka   │ │ HTTP POST    │
│ Producer│ │ to Confluent │
└────┬────┘ └──────┬───────┘
     │             │
     └──────┬──────┘
            ▼
    ┌────────────────┐
    │ Kafka Broker   │
    │ Topic: gerrit  │
    └────────────────┘
```

### Subscription Flow: Kafka → Event Processor

```
┌─────────────────────┐
│  Manager.start()    │
│  Initialize subs    │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│  BrokerApi          │
│  receiveAsync()     │
│  - Create subscriber│
│  - Start consumption│
└─────────┬───────────┘
          │
     ┌────┴────┐
     │         │
     ▼         ▼
┌──────────┐ ┌─────────────┐
│ Native   │ │ REST        │
│ Consumer │ │ Consumer    │
│ - Poll   │ │ - HTTP GET  │
│ - Loop   │ │ - Loop      │
└────┬─────┘ └──────┬──────┘
     │              │
     └──────┬───────┘
            │ ConsumerRecords
            ▼
┌──────────────────────────┐
│  KafkaEventDeserializer  │
│  deserialize(json)       │
│  - Parse JSON            │
│  - Create Event object   │
└────────┬─────────────────┘
         │ Event object
         ▼
┌──────────────────────────┐
│  Message Processor       │
│  consumer.accept(event)  │
│  - Custom handler logic  │
└──────────────────────────┘
```

## How Stream Events are Captured

The plugin integrates with Gerrit's event system through the standard `EventListener` interface:

1. **Registration** (`Module.java:62-64`):
   ```java
   if (configuration.isSendStreamEvents()) {
     DynamicSet.bind(binder(), EventListener.class).to(KafkaPublisher.class);
   }
   ```

2. **Event Reception** (`KafkaPublisher.java:50-55`):
   ```java
   @Override
   public void onEvent(Event event) {
     if (session.isOpen()) {
       session.publish(gson.toJson(event));
     }
   }
   ```

3. **Event Types**: All standard Gerrit stream events are captured:
   - `patchset-created`
   - `change-merged`
   - `change-abandoned`
   - `comment-added`
   - `ref-updated`
   - And all other Gerrit event types

4. **Event Format**: Events are serialized using Gerrit's event Gson instance, maintaining compatibility with Gerrit's standard stream-events JSON format

## Configuration

Configuration is specified in `gerrit.config` under `[plugin "events-kafka"]`.

### Essential Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `bootstrapServers` | *required* | Comma-separated Kafka broker addresses |
| `sendStreamEvents` | `false` | Enable automatic stream event publishing |
| `topic` | `"gerrit"` | Default Kafka topic for events |
| `clientType` | `NATIVE` | `NATIVE` or `REST` |

### Publishing Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `sendAsync` | `true` | Use asynchronous publishing (recommended) |

### Subscription Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `groupId` | instance-id | Kafka consumer group ID |
| `pollingIntervalMs` | `1000` | Consumer polling interval (milliseconds) |
| `numberOfSubscribers` | `7` | Thread pool size for subscribers |

### REST API Settings (when `clientType=REST`)

| Setting | Default | Description |
|---------|---------|-------------|
| `restApiUri` | *required* | Confluent REST Proxy URL |
| `restApiUsername` | - | HTTP Basic auth username |
| `restApiPassword` | - | HTTP Basic auth password |
| `restApiTimeout` | `60s` | HTTP request timeout |
| `restApiThreads` | `10` | HTTP client thread pool size |
| `httpWireLog` | `false` | Enable HTTP wire logging |

### Example Configuration

```ini
[plugin "events-kafka"]
  bootstrapServers = kafka1.example.com:9092,kafka2.example.com:9092
  sendStreamEvents = true
  topic = gerrit-events
  clientType = NATIVE
  sendAsync = true
  groupId = gerrit-cluster
  pollingIntervalMs = 1000
  numberOfSubscribers = 10
```

## Message Format

### Kafka Record Structure

- **Key**: String representation of `System.nanoTime()` at publish time
- **Value**: JSON-serialized Gerrit event (standard stream-events format)

### Example Event JSON

```json
{
  "type": "patchset-created",
  "change": {
    "project": "myproject",
    "branch": "master",
    "id": "I1234567890123456789012345678901234567890",
    "number": 12345,
    "subject": "Fix bug in authentication",
    "owner": {"name": "John Doe", "email": "john@example.com"},
    "url": "https://gerrit.example.com/12345"
  },
  "patchSet": {
    "number": 2,
    "revision": "abcdef1234567890",
    "ref": "refs/changes/45/12345/2",
    "uploader": {"name": "John Doe", "email": "john@example.com"}
  },
  "eventCreatedOn": 1234567890
}
```

## Use Cases

### 1. CI/CD Integration
External CI systems subscribe to `patchset-created` events to trigger automated builds and tests.

### 2. Multi-Site Replication
Gerrit instances in different geographic locations publish/consume events to stay synchronized.

### 3. Metrics & Analytics
Analytics systems consume events to track development velocity, review patterns, and code churn.

### 4. Custom Workflows
External systems implement custom business logic triggered by specific Gerrit events.

### 5. Audit & Compliance
Long-term event storage in Kafka for compliance and audit trail requirements.

## Threading Model

### Publisher
- **Main Thread**: Gerrit's event dispatcher calls `onEvent()` synchronously
- **Async Mode**: Kafka producer uses background I/O threads for network operations
- **Sync Mode**: Blocks Gerrit event thread until Kafka acknowledges (not recommended)

### Subscriber
- **Native Client**: One dedicated thread per subscriber (ReceiverJob), configured via `numberOfSubscribers`
- **REST Client**: Thread pool executor with `numberOfSubscribers` threads
- **Message Processing**: Each message processor callback runs in subscriber thread

## Error Handling

### Publishing Errors
- **Async Mode**: Failures logged via callback, future resolves to `false`
- **Sync Mode**: Exceptions propagated to caller
- **Metrics**: Success/failure counts tracked per topic

### Subscription Errors
- **Deserialization Failures**: Logged and skipped, consumption continues
- **Processing Exceptions**: Logged, consumption continues with next message
- **Connection Failures**: Automatic reconnection via Kafka client retry logic

## Deployment Considerations

### Native Client (Recommended)
- **Pros**: Better performance, lower latency, more features
- **Cons**: Requires direct network access to Kafka brokers
- **When to Use**: Standard deployments with network access to Kafka

### REST Client
- **Pros**: Works through HTTP proxies, simpler firewall rules
- **Cons**: Higher latency, additional REST proxy infrastructure
- **When to Use**: Restricted networks, environments requiring HTTP-only communication

### Performance Tuning
- Use **async publishing** for maximum throughput
- Increase `numberOfSubscribers` for high-volume event consumption
- Tune Kafka producer settings (`batch.size`, `linger.ms`) based on latency requirements
- Configure appropriate `pollingIntervalMs` to balance latency vs. CPU usage

## Dependencies

### Core Libraries
- **Apache Kafka Client**: `kafka-clients` library (version in BUILD file)
- **Gerrit Plugin API**: Gerrit's plugin infrastructure
- **Events Broker API**: Generic broker abstraction layer

### REST Client Dependencies
- **Apache HttpClient**: HTTP client library for REST proxy communication
- **HttpComponents**: HTTP utilities and connection management

## Security

### Authentication
- **Native Client**: Supports SASL/SSL authentication via Kafka properties
- **REST Client**: HTTP Basic authentication via `restApiUsername`/`restApiPassword`

### Authorization
- Kafka topic ACLs control which events can be published/consumed
- Gerrit's plugin configuration restricts who can modify settings

### Encryption
- **Native Client**: SSL/TLS via Kafka client configuration
- **REST Client**: HTTPS via REST proxy URI scheme

## Monitoring & Observability

### Metrics
The plugin tracks publishing metrics per topic (implementation in KafkaSession):
- Success count
- Failure count
- Latency (for sync mode)

### Logging
- **Publisher**: Logs connection status, publishing errors
- **Subscriber**: Logs polling activity, deserialization failures
- **HTTP Wire Log**: Detailed HTTP request/response logging for REST client (when `httpWireLog=true`)

## Extension Points

### Custom Event Consumers
Other plugins can use `BrokerApi` to consume events:

```java
@Inject
BrokerApi brokerApi;

brokerApi.receiveAsync("gerrit", event -> {
  // Custom processing logic
});
```

### Custom Event Publishers
Other plugins can publish custom events:

```java
@Inject
BrokerApi brokerApi;

brokerApi.send("custom-topic", customEvent);
```

## Implementation Notes

### Classloader Management
The plugin carefully manages classloader context when interacting with Kafka clients (`KafkaSession.java:77-88, 103-107`) to avoid classloader issues in Gerrit's plugin environment.

### Deserialization
`KafkaEventDeserializer` handles conversion from Kafka message bytes to Gerrit `Event` objects, supporting UTF-8 encoding and proper error handling.

### Message Key Strategy
Using `System.nanoTime()` as the message key provides:
- **Ordering**: Events from same JVM maintain relative ordering within partition
- **Distribution**: High-cardinality keys distribute events across partitions
- **Simplicity**: No need for complex key generation logic

### Future Enhancements
- Support for Avro/Protobuf event serialization
- Event filtering at publisher level
- Dead letter queue for failed messages
- Enhanced metrics (JMX, Prometheus)
- Schema registry integration

## References

- [Gerrit Plugin Development](https://gerrit-review.googlesource.com/Documentation/dev-plugins.html)
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Confluent REST Proxy API](https://docs.confluent.io/platform/current/kafka-rest/api.html)
- [Events Broker API](https://gerrit.googlesource.com/modules/events-broker/)
