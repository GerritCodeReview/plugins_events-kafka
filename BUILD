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
        "Gerrit-InitStep: com.googlesource.gerrit.plugins.kafka.InitConfig",
        "Gerrit-Module: com.googlesource.gerrit.plugins.kafka.Module",
        "Implementation-Title: Gerrit Apache Kafka plugin",
        "Implementation-URL: https://gerrit.googlesource.com/plugins/events-kafka",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "@events-broker//jar",
        "@kafka-client//jar",
    ],
)

junit_tests(
    name = "events_kafka_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    tags = ["events-kafka"],
    deps = [
        ":events-kafka__plugin_test_deps",
        "@events-broker//jar",
        "@kafka-client//jar",
    ],
)

java_library(
    name = "events-kafka__plugin_test_deps",
    testonly = 1,
    visibility = ["//visibility:public"],
    exports = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        ":events-kafka__plugin",
        "@testcontainers-kafka//jar",
        "@jackson-annotations//jar",
        "@testcontainers//jar",
        "@docker-java-api//jar",
        "@docker-java-transport//jar",
        "@duct-tape//jar",
        "@visible-assertions//jar",
        "@jna//jar",
    ],
)
