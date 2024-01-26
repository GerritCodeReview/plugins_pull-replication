@PLUGIN@ health checks
==============

The @PLUGIN@ plugin registers the `pull-replication-outstanding-tasks`
healthcheck. This check will mark a gerrit instance as healthy upon
startup only when the node has caught up with all the outstanding
pull-replication tasks. The goal is to mark the node as healthy when it
is ready to receive write traffic. "Caught up" means:

- All pending & in-flight replication tasks across all sources (and
across a configurable set of repos) have completed
- There are no queued replication tasks pending and the above condition
lasts for at least N seconds (configurable)

See [Healthcheck based on replication tasks](https://issues.gerritcodereview.com/issues/312895374) for more details.

**It is worth noting that once the healthcheck eventually succeeds and
the instance is marked healthy, the check is then skipped (ie any
subsequent invocations will always mark the instance as healthy
irrespective of any pending or inflight tasks being present).**

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
Multiple entries are supported. If not specified, all the outstanding
replication tasks are tracked.
- `healthcheck.@PLUGIN@-outstanding-tasks.periodOfTime`: The time for
which the check needs to be successful, in order for the instance to be
marked healthy. If the time unit is omitted it defaults to milliseconds.
Values should use common unit suffixes to express their setting:

* ms, milliseconds
* s, sec, second, seconds
* m, min, minute, minutes
* h, hr, hour, hours

Default is 10s.

This example config will report the node healthy when there are no
pending tasks for the `foo` and `bar/baz` repos continuously for a
period of 5 seconds after the plugin startup.
```
[healthcheck "pull-replication-tasks"]
    projects = foo
    projects = bar/baz
    periodOfTime = 5 sec
```

Useful information
------------------

- The health check is registered only when the [healthcheck](https://gerrit.googlesource.com/plugins/healthcheck) plugin
is installed. If the `healthcheck` plugin is not installed, then the
check registration is skipped during load of the pull-replication
plugin.
- Because the pull-replication healthcheck depends on the `healthcheck` plugin, renaming/removing the `healthcheck`
jar file is not supported during runtime. Doing so can lead to unpredictable behaviour of your gerrit instance.

