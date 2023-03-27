@PLUGIN@ extension points
==============

The @PLUGIN@ plugin exposes an extension point to allow influencing its
behaviour from another plugin or a script.
Extension points are available only when the plugin extension points module
is loaded as [libModule](/config-gerrit.html#gerrit.installModule) and
implemented by another plugin which depends on this as `provided`
dependency.

### Install extension libModule

The replication plugin's extension points are defined in the
`c.g.g.p.r.p.ReplicationExtensionPointModule` that needs to be configured
as libModule.

Create a symbolic link from `$GERRIT_SITE/plugins/@PLUGIN@.jar` into
`$GERRIT_SITE/lib` and then add the @PLUGIN@ extension module to the
`gerrit.config`.

Example:

```
[gerrit]
  installModule = com.googlesource.gerrit.plugins.replication.pull.ReplicationExtensionPointModule
```

> **NOTE**: Use and configuration of the @PLUGIN@ plugin as library module
requires a Gerrit server restart and does not support hot plugin install or
upgrade.


### Extension points

* `com.googlesource.gerrit.plugins.replication.pull.ReplicationFetchFilter`

  Filter out the refs fetched from a remote instance.
  Only one filter at a time is supported. Filter implementation needs to
  bind a `DynamicItem`.

  Default: no filtering

  Example:

  ```
  DynamicItem.bind(binder(), ReplicationFetchFilter.class).to(ReplicationFetchFilterImpl.class);
  ```
