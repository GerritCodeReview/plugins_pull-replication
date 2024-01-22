@PLUGIN@ health checks
==============

The @PLUGIN@ plugin registers the `pull-replication-tasks` healthcheck.
This check will mark a gerrit instance as healthy upon startup only when
the node has caught up with all the pending pull-replication tasks. 
"Caught up" means:

- All in-flight replication tasks have completed
- There are no queued replication tasks pending
and the above condition lasts for at least N seconds (configurable).
See [Healthcheck based on replication tasks](https://issues.gerritcodereview.com/issues/312895374) for more details.

Health check configuration
--------------------------

The configuration of the health check is split across two files.
- The "core" settings, ie the `enabled` flag and the check timeout
are set in the `healthcheck` plugin's [config file](https://gerrit.googlesource.com/plugins/healthcheck/+/refs/heads/master/src/main/resources/Documentation/config.md#settings).
- Settings specific to the check are set in the plugin's [config file](https://gerrit.googlesource.com/plugins/pull-replication/+/refs/heads/master/src/main/resources/Documentation/config.md#file-2).


The health check can be configured as follows:
- `healthcheck.NAME.projects`: The repo(s) that the health check will
track pending replication tasks against. Multiple entries are supported.
- `healthcheck.NAME.periodOfTime`: The time in seconds for which the 
check needs to be successful, in order for the instance to be marked
healthy.

This example config will report the node healthy when there are no
pending tasks for the `foo` and `bar/baz` repos continuously for a
period of 5 seconds.
```
[healthcheck "pull-replication-tasks"]
    projects = foo
    projects = bar/baz
    periodOfTime = 5 
```

Useful information
------------------

- The health check is registered only when the [healthcheck](https://gerrit.googlesource.com/plugins/healthcheck) plugin
is installed. If the `healthcheck` plugin is not installed, then the
check registration is skipped during load of the pull-replication
plugin.
- The caveats of external health checks are applicable. Please read the
`Gotchas And Limitations` of the `Gerrit-ApiModule` section in the [docs](https://gerrit.googlesource.com/gerrit/+/refs/heads/master/Documentation/dev-plugins.txt).

