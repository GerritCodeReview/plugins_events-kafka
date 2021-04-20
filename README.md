Kafka: Gerrit event producer for Apache Kafka
=======================

* Author: GerritForge
* Repository: https://gerrit.googlesource.com/plugins/kafka-events
* CI/Release: https://gerrit-ci.gerritforge.com/search/?q=kafka-events

[![Build Status](https://gerrit-ci.gerritforge.com/job/plugin-kafka-events-bazel-master/lastBuild/badge/icon)](https://gerrit-ci.gerritforge.com/job/plugin-kafka-events-bazel-master/lastBuild/)

Synopsis
----------------------

This plugins allows to define a distributed stream of events
published by Gerrit.

Events can be anything, from the traditional stream events
to the Gerrit metrics.

This plugin requires Gerrit 2.13 or laster.

Environments
---------------------

* `linux`
* `java-1.8`
* `Bazel`

Build
---------------------
Kafka plugin can be build as a regular 'in-tree' plugin. That means that is required to
clone a Gerrit source tree first and then to have the Kafka plugin source directory into
the /plugins path. Additionally, the plugins/external_plugin_deps.bzl file needs to be
updated to match the Kafka plugin one.

    git clone --recursive https://gerrit.googlesource.com/gerrit
    git clone https://gerrit.googlesource.com/plugins/kafka-events gerrit/plugins/kafka-events
    cd gerrit
    rm plugins/external_plugin_deps.bzl
    ln -s ./kafka-events/external_plugin_deps.bzl plugins/.

To build the kafka-events plugins, issue the command from the Gerrit source path:

    bazel build plugins/kafka-events

The output is created in

    bazel-genfiles/plugins/kafka-events/kafka-events.jar

Minimum Configuration
---------------------
Assuming a running Kafka broker on the same Gerrit host, add the following
settings to gerrit.config:

```
  [plugin "kafka-events"]
    bootstrapServers = localhost:9092
```

