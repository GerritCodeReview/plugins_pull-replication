load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps():
    maven_jar(
        name = "events-broker",
        artifact = "com.gerritforge:events-broker:3.5.1",
        sha1 = "78b8bc6ad7fd7caadcc1c6e3484332464de0ac38",
    )
