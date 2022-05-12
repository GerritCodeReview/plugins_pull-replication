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
        "Gerrit-SshModule: com.googlesource.gerrit.plugins.replication.pull.SshModule",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.replication.pull.api.HttpModule"
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "//lib/commons:io",
        "//plugins/replication:replication",
    ],
)

junit_tests(
    name = "pull_replication_tests",
    srcs = glob([
        "src/test/java/**/*Test.java",
        "src/test/java/**/*IT.java",
    ]),
    tags = ["pull-replication"],
    visibility = ["//visibility:public"],
    deps = PLUGIN_TEST_DEPS + PLUGIN_DEPS + [
        ":pull-replication__plugin",
        ":pull_replication_util",
        "//plugins/replication:replication",
        "//plugins/delete-project:delete-project",
    ],
)

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
         "//plugins/replication:replication",
    ],
)
