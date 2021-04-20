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
        artifact = "com.gerritforge:events-broker:3.1.3",
        sha1 = "a12ef44f9b75a5dbecac9f1f0acf0f236b220252",
    )
