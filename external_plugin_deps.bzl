load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps():
    maven_jar(
        name = "events-broker",
        artifact = "com.gerritforge:events-broker:3.7.0-rc2",
        sha1 = "481f0e113f82a49b58f1c1032c053ab6f44a258d",
    )
