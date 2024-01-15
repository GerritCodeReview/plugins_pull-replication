load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS", "gerrit_plugin")

gerrit_plugin(
    name = "pull-replication",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Implementation-Title: Pull Replication plugin",
        "Implementation-URL: https://gerrit-review.googlesource.com/#/admin/projects/plugins/pull-replication",
        "Gerrit-PluginName: pull-replication",
        "Gerrit-Module: com.googlesource.gerrit.plugins.replication.pull.PullReplicationModule",
        "Gerrit-InitStep: com.googlesource.gerrit.plugins.replication.pull.InitPlugin",
        "Gerrit-SshModule: com.googlesource.gerrit.plugins.replication.pull.SshModule",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.replication.pull.api.HttpModule",
        "Gerrit-ReloadMode: restart",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        ":events-broker-neverlink",
        "//lib/commons:io",
        "//plugins/delete-project",
        "//plugins/replication",
        "@commons-lang3//jar",
        ":healthcheck-neverlink",
    ],
)

junit_tests(
    name = "pull_replication_tests",
    size = "large",
    srcs = glob([
        "src/test/java/**/*Test.java",
    ]),
    tags = ["pull-replication"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":pull-replication__plugin",
        ":pull_replication_util",
        "//plugins/delete-project",
        "//plugins/replication",
        "//plugins/events-broker",
    ],
)

[junit_tests(
    name = f[:f.index(".")].replace("/", "_"),
    srcs = [f],
    tags = ["pull-replication"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":pull-replication__plugin",
        ":pull_replication_util",
        "//plugins/replication",
    ],
) for f in glob(["src/test/java/**/*IT.java"])]

java_library(
    name = "pull_replication_util",
    testonly = True,
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = [
            "src/test/java/**/*Test.java",
            "src/test/java/**/*IT.java",
        ],
    ),
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":pull-replication__plugin",
        "//plugins/delete-project",
        "//plugins/replication",
    ],
)

java_library(
    name = "pull-replication__plugin_test_deps",
    testonly = 1,
    visibility = ["//visibility:public"],
    exports = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        ":pull-replication__plugin",
        "//plugins/events-broker",
    ],
)

java_library(
    name = "events-broker-neverlink",
    neverlink = 1,
    exports = ["//plugins/events-broker"],
)

java_library(
    name = "healthcheck-neverlink",
    neverlink = 1,
    exports = ["//plugins/healthcheck"],
)
