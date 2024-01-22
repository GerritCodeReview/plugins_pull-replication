@PLUGIN@ health checks
==============

The @PLUGIN@ plugin registers the `pull-replication-outstanding-tasks`
healthcheck.

Health check configuration
--------------------------

The configuration of the health check is split across two files.
- The "standard" properties commonly available to all other checks
of the `healthcheck` plugin. These are set in the `healthcheck` plugin's
[config file](https://gerrit.googlesource.com/plugins/healthcheck/+/refs/heads/master/src/main/resources/Documentation/config.md#settings).
- Settings specific to the check are set in the plugin's [config file](./config.md#file-pluginconfig).


The health check can be configured as follows:
- `healthcheck.@PLUGIN@-outstanding-tasks.projects`: The repo(s) that
the health check will track pending replication tasks against. Multiple
entries are supported.
- `healthcheck.@PLUGIN@-outstanding-tasks.periodOfTime`: The time for
which the check needs to be successful, in order for the instance to be
marked healthy. If the time unit is omitted it defaults to seconds.

Useful information
------------------

- The health check is registered only when the [healthcheck](https://gerrit.googlesource.com/plugins/healthcheck) plugin
is installed. If the `healthcheck` plugin is not installed, then the
check registration is skipped during load of the pull-replication
plugin.
- The caveats of external health checks are applicable. Please read the
`Gotchas And Limitations` of the `Gerrit-ApiModule` section in the [docs](https://gerrit.googlesource.com/gerrit/+/refs/heads/master/Documentation/dev-plugins.txt).

