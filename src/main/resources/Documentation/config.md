Apache Kafka Configuration
======================

Some parameters can be configured using Gerrit config file.

Sample config
---------------------

```
[plugin "@PLUGIN@"]
        bootstrapServers = localhost:9092
```

All the Apache Kafka properties configuration needs to
be defined in gerrit.config using a lower camel-case notation.

Example: bootstrapServers correspond to the Apache Kafka property
bootstrap.servers.

See [Apache Kafka Producer Config](http://kafka.apache.org/documentation.html#producerconfigs)
for a full list of available settings and the values allowed.

Default Values
-----------------

|name                 | value
|:--------------------|:------------------
| acks                | all
| retries             | 0
| batchSize           | 16384
| lingerMs            | 1
| bufferMemory        | 33554432
| keySerializer       | org.apache.kafka.common.serialization.StringSerializer
| valueSerializer     | org.apache.kafka.common.serialization.StringSerializer

Additional properties
---------------------

`plugin.@PLUGIN@.clientType`
:	Client stack for connecting to Kafka broker:
    - `NATIVE` for using the Kafka client to connect to the broker directory
    - `REST` for using a simple HTTP client to connect to
      [Confluent REST-API Proxy](https://docs.confluent.io/platform/current/kafka-rest/index.html).
      **NOTE**: `plugin.@PLUGIN@.restApiUri` is mandatory when using a `REST` client type.
	Default: `NATIVE`

`plugin.@PLUGIN@.groupId`
:	Kafka consumer group for receiving messages.
	Default: Gerrit instance-id

`plugin.@PLUGIN@.httpWireLog`
:	Enable the HTTP wire protocol logging in error_log for all the communication with
	the [Confluent REST-API Proxy](https://docs.confluent.io/platform/current/kafka-rest/index.html).
	**NOTE**: when `plugin.@PLUGIN@.restApiUri` is unset or set to `NATIVE`, this setting is ignored.
	Default: false

`plugin.@PLUGIN@.pollingIntervalMs`
:	Polling interval in msec for receiving messages from Kafka topic subscription.
	Default: 1000

`plugin.@PLUGIN@.restApiUri`
:	URL of the
	[Confluent REST-API Proxy](https://docs.confluent.io/platform/current/kafka-rest/index.html)
	for sending/receiving messages through REST-API instead of using the native Kafka client.
	The value can use the `${kafka_rest_id}` placeholder which will be replaced at runtime using
	the `KAFKA_REST_ID` associated with the Kafka REST-API Proxy that is answering the call,
	typically needed when having multiple proxies behind a workload balancer and sticky session
	allocation isn't an option.
	**NOTE**: when `plugin.@PLUGIN@.restApiUri` is unset or set to `NATIVE`, this setting is ignored.
	Default: unset

`plugin.@PLUGIN@.restApiThreads`
:	Maximum number of concurrent client calls to the
	[Confluent REST-API Proxy](https://docs.confluent.io/platform/current/kafka-rest/index.html)
	for sending/receiving messages.
	**NOTE**: when `plugin.@PLUGIN@.restApiUri` is unset or set to `NATIVE`, this setting is ignored.
	Default: 10

`plugin.@PLUGIN@.restApiTimeout`
:	Maximum time to wait for a client call to
	[Confluent REST-API Proxy](https://docs.confluent.io/platform/current/kafka-rest/index.html)
	to complete. This setting is also applied as TCP socket connection and read/write timeout
	for the outgoing HTTP calls.
	The value is expressed using the `N unit` format of all other Gerrit time expressions, using
	one of the following units:
	- s, sec, second, seconds
	- m, min, minute, minutes
	- h, hr, hour, hours
	**NOTE**: when `plugin.@PLUGIN@.restApiUri` is unset or set to `NATIVE`, this setting is ignored.
	Default: 60 sec

`plugin.@PLUGIN@.sendAsync`
:	Send messages to Kafka asynchronously, detaching the calling process from the
	acknowledge of the message being sent.
	Default: true

secure.config
--------------------

`plugin.@PLUGIN@.restApiUsername`
:	Username used for the authentication to the
	[Confluent REST-API Proxy](https://docs.confluent.io/platform/current/kafka-rest/index.html)
	for sending/receiving messages through REST-API instead of using the native Kafka client.
	**NOTE**: when `plugin.@PLUGIN@.restApiUri` is unset or set to `NATIVE`, this setting is ignored.
	Default: unset

`plugin.@PLUGIN@.restApiPassword`
:	Password used for the authentication to the
	[Confluent REST-API Proxy](https://docs.confluent.io/platform/current/kafka-rest/index.html)
	**NOTE**: when `plugin.@PLUGIN@.restApiUri` is unset or set to `NATIVE`, this setting is ignored.
	Default: unset
