load("//tools/bzl:maven_jar.bzl", "MAVEN_LOCAL", "maven_jar")

def external_plugin_deps():
    maven_jar(
        name = "events-broker",
        artifact = "com.gerritforge:events-broker:3.4.8.1",
        repository = MAVEN_LOCAL,
    )
