load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps():
    maven_jar(
        name = "kafka-client",
        artifact = "org.apache.kafka:kafka-clients:2.1.0",
        sha1 = "34d9983705c953b97abb01e1cd04647f47272fe5",
    )

    maven_jar(
        name = "testcontainers-kafka",
        artifact = "org.testcontainers:kafka:1.15.0",
        sha1 = "d34760b11ab656e08b72c1e2e9b852f037a89f90",
    )

    maven_jar(
        name = "events-broker",
        artifact = "com.gerritforge:events-broker:3.3.0-rc7",
        sha1 = "5efe1c4a0f7c385b0ec95b8f9897248049c4173c",
    )