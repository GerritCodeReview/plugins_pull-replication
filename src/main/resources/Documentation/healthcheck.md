@PLUGIN@ health checks
==============

The @PLUGIN@ plugin registers the `pull-replication-outstanding-tasks`
healthcheck. This check will mark a gerrit instance as healthy
only when the node has caught up with all the outstanding
pull-replication tasks. The goal is to mark the node as healthy when it
is ready to receive write traffic. "Caught up" means:

- All pending & in-flight replication tasks across all sources (or
across a configurable set of repos) have completed

See [Healthcheck based on replication tasks](https://issues.gerritcodereview.com/issues/312895374) for more details.


Health check configuration
--------------------------

The configuration of the health check is split across two files.
- The "standard" properties commonly available to all other checks
of the `healthcheck` plugin. These are set in the `healthcheck` plugin's
[config file](https://gerrit.googlesource.com/plugins/healthcheck/+/refs/heads/master/src/main/resources/Documentation/config.md#settings).
- Settings specific to the check are set in the plugin's [config file](./config.md#file-pluginconfig).


The health check can be configured as follows:
- `healthcheck.@PLUGIN@-outstanding-tasks.projects`: The repo(s) that
the health check will track outstanding replication tasks against.
Multiple entries are supported.
- `healthcheck.@PLUGIN@-outstanding-tasks.periodOfTime`: The time for
which the check needs to be successful, in order for the instance to be
marked healthy. If the time unit is omitted it defaults to seconds.
Values should use common unit suffixes to express their setting:

* ms, milliseconds
* s, sec, second, seconds
* m, min, minute, minutes
* h, hr, hour, hours


Useful information
------------------

- The health check is registered only when the [healthcheck](https://gerrit.googlesource.com/plugins/healthcheck) plugin
is installed. If the `healthcheck` plugin is not installed, then the
check registration is skipped during load of the pull-replication
plugin.
- Because the pull-replication healthcheck depends on the `healthcheck` plugin, renaming/removing the `healthcheck`
jar file is not supported during runtime. Doing so can lead to unpredictable behaviour of your gerrit instance.

