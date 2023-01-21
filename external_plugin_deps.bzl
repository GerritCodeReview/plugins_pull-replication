load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps():
    maven_jar(
        name = "events-broker",
        artifact = "com.gerritforge:events-broker:3.5.0.1",
        sha1 = "af192a8bceaf7ff54d19356f9bfe1f1e83634b40",
    )
