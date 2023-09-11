load("//tools/bzl:junit.bzl", "junit_tests")
load(
    "//tools/bzl:plugin.bzl",
    "PLUGIN_DEPS",
    "PLUGIN_TEST_DEPS",
    "gerrit_plugin",
)

gerrit_plugin(
    name = "events-kafka",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: events-kafka",
        "Gerrit-Module: com.googlesource.gerrit.plugins.kafka.Module",
        "Implementation-Title: Gerrit Apache Kafka plugin",
        "Implementation-URL: https://gerrit.googlesource.com/plugins/events-kafka",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "//lib/httpcomponents:httpclient",
        "//plugins/events-broker",
        "@httpasyncclient//jar",
        "@httpcore-nio//jar",
        "@kafka-client//jar",
    ],
)

junit_tests(
    name = "events_kafka_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    resources = glob(["src/test/resources/**/*"]),
    tags = ["events-kafka"],
    timeout = "long",
    deps = [
        ":events-kafka__plugin_test_deps",
        "//lib/testcontainers",
        "//plugins/events-broker",
        "@kafka-client//jar",
        "@testcontainers-kafka//jar",
    ],
)

java_library(
    name = "events-kafka__plugin_test_deps",
    testonly = 1,
    visibility = ["//visibility:public"],
    exports = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        ":events-kafka__plugin",
        "@testcontainers-kafka//jar",
        "//lib/jackson:jackson-annotations",
        "//lib/testcontainers",
        "//lib/testcontainers:docker-java-api",
        "//lib/testcontainers:docker-java-transport",
    ],
)
