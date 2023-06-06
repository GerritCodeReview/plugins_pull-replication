@PLUGIN@ Configuration
=========================

Enabling Replication
--------------------

If replicating over SSH, ensure the host key of the
remote system(s) is already in the Gerrit user's `~/.ssh/known_hosts`
file.  The easiest way to add the host key is to connect once by hand
with the command line:

```
  sudo su -c 'ssh mirror1.us.some.org echo' gerrit2
```

<a name="example_file">
Next, create `$site_path/etc/replication.config` as a Git-style config
file, for example to replicate in parallel from four different hosts:</a>

```
  [remote "host-two"]
    url = gerrit2@host-two.example.com:/some/path/${name}.git
    apiUrl = http://host-two.example.com:8080

  [remote "pubmirror"]
    url = mirror1.us.some.org:/pub/git/${name}.git
    url = mirror2.us.some.org:/pub/git/${name}.git
    url = mirror3.us.some.org:/pub/git/${name}.git
    fetch = +refs/heads/*:refs/heads/*
    fetch = +refs/tags/*:refs/tags/*
    threads = 3
    authGroup = Public Mirror Group
    authGroup = Second Public Mirror Group
```

And make sure that instanceId is setup in `$site_path/etc/gerrit.config`:

```
[gerrit]
    instanceId = host-one
```

Then reload the replication plugin to pick up the new configuration:

```
  ssh -p 29418 localhost gerrit plugin reload @PLUGIN@
```

To manually trigger replication at runtime, see
SSH command [start](cmd-start.md).

File `gerrit.config`
-------------------------

cache.@PLUGIN@-apply_objects.maxAge
:	Maximum age to keep history of the latest successful apply-object refs.
	Values should use common unit suffixes to express their setting:

	s, sec, second, seconds

	m, min, minute, minutes

	h, hr, hour, hours

	d, day, days

	w, week, weeks (1 week is treated as 7 days)

	mon, month, months (1 month is treated as 30 days)

	y, year, years (1 year is treated as 365 days)

	If a unit suffix is not specified, seconds is assumed. If 0 is supplied, the maximum age
	is infinite and items are never purged except when the cache is full.

	Default is 60s.

cache.@PLUGIN@-apply_objects.memoryLimit
:	The maximum number of apply-object refs retained in memory.

	Default is 1024.

event.stream-events.enableBatchRefUpdatedEvents
: Boolean flag controlling the use of batch ref update events (ie multiple
  refs updated together in a single `batch-ref-updated` event).

  Default is false.


File `@PLUGIN@.config`
-------------------------

The optional file `$site_path/etc/replication.config` is a Git-style
config file that controls the replication settings for the replication
plugin.

The file is composed of one or more `remote` sections, each remote
section provides common configuration settings for one or more
source URLs.

Each remote section uses its own thread pool.  If fetching from
multiple remotes, over differing types of network connections
(e.g. LAN and also public Internet), its a good idea to put them
into different remote sections, so that replication to the slower
connection does not starve out the faster local one.  The example
file above does this.

In the keys below, the `NAME` portion is unused by this plugin, but
must be unique to distinguish the different sections if more than one
remote section appears in the file.

gerrit.replicateOnStartup
:	If true, replicates from all remotes on startup to ensure they
	are in-sync with this server.  By default, false.

gerrit.autoReload
:	If true, automatically reloads replication sources and settings
	after `replication.config` file is updated, without the need to restart
	the replication plugin. When the reload takes place, pending replication
	events based on old settings are discarded. By default, false.

replication.lockErrorMaxRetries
:	Number of times to retry a replication operation if a lock
	error is detected.

	If two or more replication operations (to the same GIT and Ref)
	are scheduled at approximately the same time (and end up on different
	replication threads), there is a large probability that the last
	fetch to complete will fail with a remote "failure to lock" error.
	This option allows Gerrit to retry the replication fetch when the
	"failure to lock" error is detected.

	A good value would be 3 retries or less, depending on how often
	you see lockError collisions in your server logs. A too highly set
	value risks keeping around the replication operations in the queue
	for a long time, and the number of items in the queue will increase
	with time.

	Normally Gerrit will succeed with the replication during its first
	retry, but in certain edge cases (e.g. a mirror introduces a ref
	namespace with the same name as a branch on the master) the retry
	will never succeed.

	The issue can also be mitigated somewhat by increasing the
	replicationDelay.

	Default: 0 (disabled, i.e. never retry)

replication.maxRetries
:	Maximum number of times to retry a fetch operation that previously
	failed.

	When a fetch operation reaches its maximum number of retries,
	the replication event is discarded from the queue.

	Can be overridden at remote-level by setting replicationMaxRetries.

	By default, fetches are retried indefinitely.

	Note that only transient errors will be retried, whilst persistent errors will
	cause a terminal failure, and the fetch will not be scheduled again. This is
	only supported for JGit, not cGit. Currently, only the following failures are
	considered permanent:

	- UnknownHostKey: thrown by Jsch when establishing an SSH connection for an
	unknown host.
	- cGit / JGit transport exception when the remote ref does not exist. The assumption
	here is that the remote ref does not exist so it is not worth retrying. If the
	exception arisen as a consequence of some ACLs (mis)configuration, then after
	fixing the ACLs, an explicit replication must be manually triggered.

replication.instanceLabel
:	Remote configuration name of the current server.
	This label is passed as a part of the payload to notify other
	servers to fetch specified ref-update object id from the url
	provided in the remote configuration section which name is equal
	to instanceLabel.

	Deprecated: This property is kept for backward compatibility and
	will be removed in the future release. Use [gerrit.instanceId](https://gerrit-review.googlesource.com/Documentation/config-gerrit.html#gerrit.instanceId)
	instead.

replication.consumeStreamEvents
:	Use stream events to trigger pull-replication actions alongside the
	REST approach. This mechanism is useful together with event-broker
	and multi-site to provide backfill mechanism when a node has to
	catch up with the events after being unreachable.

	When `consumeStreamEvents` is enabled gerrit.instanceId
	instead of [replication.instanceLabel](https://gerrit.googlesource.com/plugins/pull-replication/+/refs/heads/stable-3.4/src/main/resources/Documentation/config.md#replication.instanceLabel) must be used.

	Using pull-replication standalone with a broker is also possible.
	Check the replication.eventBrokerTopic parameter.

	Default: false

replication.eventBrokerTopic
:	Topic to consumer stream events from.

	Pull-replication can receive stream events and use it as
	notification mechanism as alternative to REST API notifications.
	It can work in standalone, not necessarily with the multi-site plugin
	(check replication.consumerStreamEvents if you need to use it with
	multi-site). This parameter is used to define the topic to consume
	stream events messages from when using the pull-replication in
	standalone with a broker.

	When `eventBrokerTopic` is enabled gerrit.instanceId
	instead of [replication.instanceLabel](https://gerrit.googlesource.com/plugins/pull-replication/+/refs/heads/stable-3.4/src/main/resources/Documentation/config.md#replication.instanceLabel)
	must be used.

	Bear in mind that if consumeStreamEvents is set to true this
	parameter will be ignored.

	Default: unset

replication.maxConnectionsPerRoute
:	Maximum number of HTTP connections per one HTTP route.

	Default: 100

replication.maxConnections
:	Total number of HTTP connections pool.

	Default: 2 * replication.maxConnectionsPerRoute

replication.useCGitClient
:	By default Gerrit uses JGit library to execute all git protocol command.
	By setting this property to true all git fetch operation are going to be
	executed using CGit client instead of JGit.

	Default: false

replication.refsBatchSize
:	Number of refs that are fetched in a single fetch call.
	If number of refs to fetch is greater then this param,
	refs are going to be split into a separate fetch operations.

	Value must be greater than zero.

	Default: 50

replication.excludeRefs
:   Specify which refs should be excluded from git fetch calls. It can be provided
    more than once, and supports three formats: regular expressions, wildcard
    matching, and single ref matching. All three formats match are case-sensitive.

    Values starting with a caret `^` are treated as regular
    expressions. For the regular expressions details please follow
    official [java documentation](https://docs.oracle.com/javase/tutorial/essential/regex/).

    Please note that regular expressions could also be used
    with inverse match.

    Values that are not regular expressions and end in `*` are
    treated as wildcard matches. Wildcards match refs whose
    name agrees from the beginning until the trailing `*`. So
    `foo/b*` would match the refs `foo/b`, `foo/bar`, and
    `foo/baz`, but neither `foobar`, nor `bar/foo/baz`.

    Values that are neither regular expressions nor wildcards are
    treated as single ref matches. So `foo/bar` matches only
    the ref `foo/bar`, but no other refs.

    By default, the following refs are excluded from the git fetch calls:
    - refs/users/*
    - refs/meta/config
    - refs/sequences/*
    - refs/meta/external-ids
    - refs/groups/*
    - refs/meta/group-names
    - refs/cache-automerge/*
    - refs/starred-changes/*

    Note that if you are using @PLUGIN@ together with multi-site, you should
    explicitly exclude `refs/multi-site/version` from being replicated.

replication.syncRefs
:   Specify for which refs git fetch calls should be executed synchronously.
    It can be provided more than once, and supports three formats: regular expressions,
    wildcard matching, and single ref matching. All three formats match are case-sensitive.

    Values starting with a caret `^` are treated as regular
    expressions. For the regular expressions details please follow
    official [java documentation](https://docs.oracle.com/javase/tutorial/essential/regex/).

    Please note that regular expressions could also be used
    with inverse match.

    Values that are not regular expressions and end in `*` are
    treated as wildcard matches. Wildcards match refs whose
    name agrees from the beginning until the trailing `*`. So
    `foo/b*` would match the refs `foo/b`, `foo/bar`, and
    `foo/baz`, but neither `foobar`, nor `bar/foo/baz`.

    Values that are neither regular expressions nor wildcards are
    treated as single ref matches. So `foo/bar` matches only
    the ref `foo/bar`, but no other refs.

    By default, set to '*' (all refs are replicated synchronously).

replication.fallbackToApplyObjectsRefs
:   Specify for which refs will fallback to apply-objects REST-API that is capable of applying
an entire chain of commits on the ref chain so that the Git fetch can be avoided altogether.
It can be provided more than once, and supports three formats: regular expressions,
wildcard matching, and single ref matching. All three formats matches are case-sensitive.

    Values starting with a caret `^` are treated as regular
    expressions. For the regular expressions details please follow
    official [java documentation](https://docs.oracle.com/javase/tutorial/essential/regex/).

    Please note that regular expressions could also be used
    with inverse match.

    Values that are not regular expressions and end in `*` are
    treated as wildcard matches. Wildcards match refs whose
    name agrees from the beginning until the trailing `*`. So
    `foo/b*` would match the refs `foo/b`, `foo/bar`, and
    `foo/baz`, but neither `foobar`, nor `bar/foo/baz`.

    Values that are neither regular expressions nor wildcards are
    treated as single ref matches. So `foo/bar` matches only
    the ref `foo/bar`, but no other refs.

    By default, set to 'refs/changes/**/meta'.

    Note that the refs/changes/**/meta always fallback to apply-objects REST-API

replication.maxApiPayloadSize
:	Maximum size in bytes of the ref to be sent as a REST Api call
	payload. For refs larger than threshold git fetch operation
	will be used.

	Default: 10000

remote.NAME.url
:	Address of the remote server to fetch from. Single URL can be
	specified within a single remote block. A remote node can request
	a fetch from a source. However, it will need to be sure that the
	source corresponds to only *him* configured as remote. Fetching from
	multiple sources at the same time, whilst it was useful in a push scenario,
	doesn't make so much sense in a pull-replication flow.
	Within each URL value the magic placeholder `${name}` is
	replaced with the Gerrit project name.  This is a Gerrit
	specific extension to the otherwise standard Git URL syntax
	and it must be included in each URL so that Gerrit can figure
	out where each project needs to be replicated. `${name}` may
	only be omitted if the remote refers to a single repository
	(i.e.: Exactly one [remote.NAME.projects][3] and that name's
	value is a single project match.).

	See [git fetch][1] for details on Git URL syntax.

[1]: http://www.git-scm.com/docs/git-fetch#URLS
[3]: #remote.NAME.projects

remote.NAME.apiUrl
:	Address of the rest api endpoint of the remote server to fetch from.
	Multiple URLs may be specified within a single remote block, listing
	different destinations which share the same settings. Gerrit calls
	all URLs in sequence.

remote.NAME.connectionTimeout
:	Defines the socket timeout ({@code SO_TIMEOUT}) in milliseconds,
	which is the timeout for waiting for data or, put differently,
	a maximum period inactivity between two consecutive data packets.

	Default: 5000

remote.NAME.idleTimeout
:	Defines period of inactivity in milliseconds after which persistent connections must
	be re-validated prior to being leased to the consumer. Non-positive value disables 
	connection validation. This check helps detect connections that have become stale
	(half-closed) while kept inactive in the pool.

	Default: 10000

remote.NAME.uploadpack
:	Path of the `git-upload-pack` executable on the remote system,
	if using the SSH transport.

	Defaults to `git-upload-pack`.

remote.NAME.fetch
:	Standard Git refspec denoting what should be replicated.
	Setting this to `+refs/heads/*:refs/heads/*` would mirror only
	the active branches, but not the change refs under
	`refs/changes/`, or the tags under `refs/tags/`.

	Multiple fetch keys can be supplied, to specify multiple
	patterns to match against.  In the [example above][2], remote
	"pubmirror" uses two fetch keys to match both `refs/heads/*`
	and `refs/tags/*`, but excludes all others, including
	`refs/changes/*`.

	Note that the `refs/meta/config` branch is only replicated
	when `replicatePermissions` is true, even if the push refspec
	is 'all refs'.

[2]: #example_file

remote.NAME.timeout
:	Number of seconds to wait for a network read or write to
	complete before giving up and declaring the remote side is not
	responding.  If 0, there is no timeout, and the push client
	waits indefinitely.

	A timeout should be large enough to mostly transfer the
	objects from the other side.  1 second may be too small for
	larger projects, especially over a WAN link, while 10-30
	seconds is a much more reasonable timeout value.

	Defaults to 0 seconds, wait indefinitely.

remote.NAME.replicationDelay
:	Time to wait before scheduling an asynchronous remote fetch
	operation. Setting the delay to 0 effectively disables the delay,
	causing the fetch to start as soon as possible.

	This is a Gerrit specific extension to the Git remote block.

	By default for asynchronous fetch, 4 seconds. For a synchronous fetch
	replicationDelay is zero.

remote.NAME.rescheduleDelay
:	Delay when rescheduling a fetch operation due to an in-flight fetch
	running for the same project.

	Cannot be set to a value lower than 3 seconds to avoid a tight loop
	of schedule/run which could cause 1K+ retries per second.

	A configured value lower than 3 seconds will be rounded to 3 seconds.

	By default, 3 seconds.

remote.NAME.replicationRetry
:	Time to wait before scheduling a remote fetch operation previously
	failed due to a remote server error.

	If a remote fetch operation fails because a remote server was
	offline, all fetch operations from the same source URL are
	blocked, and the remote fetch is continuously retried unless
	the replicationMaxRetries value is set.

	This is a Gerrit specific extension to the Git remote block.

	By default, 1 minute.

remote.NAME.replicationMaxRetries
:	Maximum number of times to retry a fetch operation that previously
	failed.

	When a fetch operation reaches its maximum number of retries
	the replication event is discarded from the queue.

	This is a Gerrit specific extension to the Git remote block.

	By default, use replication.maxRetries.

	Note that not all fetch failures are retriable. Please refer
	to `replication.maxRetries` for more information on this.

remote.NAME.threads
:	Number of worker threads to dedicate to fetching to the
	repositories described by this remote.  Each thread can fetch
	one project at a time, from one source URL.  Scheduling
	within the thread pool is done on a per-project basis.  If a
	remote block describes 4 URLs, allocating 4 threads in the
	pool will permit some level of parallel fetching.

	By default, 1 thread.

remote.NAME.createMissingRepositories
:	Replicate newly created repositories.

	By default, true.

remote.NAME.replicateProjectDeletions
:	If true, project deletions will also be replicated to the
remote site.

	By default, false, do *not* replicate project deletions.

remote.NAME.authGroup
:	Specifies the name of a group that the remote should use to
	access the repositories. Multiple authGroups may be specified
	within a single remote block to signify a wider access right.
	In the project administration web interface the read access
	can be specified for this group to control if a project should
	be replicated or not to the remote.

	By default, replicates without group control, i.e. replicates
	everything from all remotes.

remote.NAME.remoteNameStyle
:	Provides possibilities to influence the name of the source
	repository, e.g. by replacing slashes in the `${name}`
	placeholder.

	Github and Gitorious do not permit slashes "/" in repository
	names and will change them to dashes "-" at repository creation
	time.

	If this setting is set to "dash", slashes will be replaced with
	dashes in the remote repository name. If set to "underscore",
	slashes will be replaced with underscores in the repository name.

	Option "basenameOnly" makes `${name}` to be only the basename
	(the part after the last slash) of the repository path on the
	Gerrit server, e.g. `${name}` of `foo/bar/my-repo.git` would
	be `my-repo`.

	By default, "slash", i.e. remote names will contain slashes as
	they do in Gerrit.

<a name="remote.NAME.projects">remote.NAME.projects</a>
:	Specifies which repositories should be replicated from the
	remote. It can be provided more than once, and supports three
	formats: regular expressions, wildcard matching, and single
	project matching. All three formats match case-sensitive.

	Values starting with a caret `^` are treated as regular
	expressions. `^foo/(bar|baz)` would match the projects
	`foo/bar`, and `foo/baz`. Regular expressions have to fully
	match the project name. So the above example would not match
	`foo/bar2`, while `^foo/(bar|baz).*` would.

	Projects may be excluded from replication by using a regular
	expression with inverse match. `^(?:(?!PATTERN).)*$` will
	exclude any project that matches.

	Values that are not regular expressions and end in `*` are
	treated as wildcard matches. Wildcards match projects whose
	name agrees from the beginning until the trailing `*`. So
	`foo/b*` would match the projects `foo/b`, `foo/bar`, and
	`foo/baz`, but neither `foobar`, nor `bar/foo/baz`.

	Values that are neither regular expressions nor wildcards are
	treated as single project matches. So `foo/bar` matches only
	the project `foo/bar`, but no other project.

	By default, replicates without matching, i.e. replicates
	everything from all remotes.

Directory `replication`
--------------------
The optional directory `$site_path/etc/replication` contains Git-style
config files that controls the replication settings for the pull replication
plugin. When present all `remote` sections from `replication.config` file are
ignored.

Files are composed of one `remote` section. Multiple `remote` sections or any
other section makes the file invalid and skipped by the pull replication plugin.
File name defines remote section name. Each section provides common configuration
settings for one or more destination URLs. For more details how to setup `remote`
sections please refer to the `replication.config` section.

### Configuration example:

Static configuration in `$site_path/etc/replication.config`:

```
[gerrit]
    autoReload = true
    replicateOnStartup = false
[replication]
    lockErrorMaxRetries = 5
    maxRetries = 5
```

Remote sections in `$site_path/etc/replication` directory:

* File `$site_path/etc/replication/host-two.config`

 ```
 [remote]
    url = gerrit2@host-two.example.com:/some/path/${name}.git
    apiUrl = http://host-two
    fetch = +refs/*:refs/*
 ```


* File `$site_path/etc/replication/host-three.config`

 ```
  [remote]
    url = mirror1.host-three:/pub/git/${name}.git
    url = mirror2.host-three:/pub/git/${name}.git
    url = mirror3.host-three:/pub/git/${name}.git
    apiUrl = http://host-three
    fetch = +refs/heads/*:refs/heads/*
    fetch = +refs/tags/*:refs/tags/*
 ```

Pull replication plugin resolves config files to the following configuration:

```
[gerrit]
    autoReload = true
    replicateOnStartup = false
[replication]
    lockErrorMaxRetries = 5
    maxRetries = 5

[remote "host-two"]
    url = gerrit2@host-two.example.com:/some/path/${name}.git
    apiUrl = http://host-two
    fetch = +refs/*:refs/*

[remote "host-three"]
    url = mirror1.host-three:/pub/git/${name}.git
    url = mirror2.host-three:/pub/git/${name}.git
    url = mirror3.host-three:/pub/git/${name}.git
    apiUrl = http://host-three
    fetch = +refs/heads/*:refs/heads/*
    fetch = +refs/tags/*:refs/tags/*
```

File `secure.config`
--------------------

The optional file `$site_path/secure.config` is a Git-style config
file that provides secure values that should not be world-readable,
such as passwords. The HTTP authentication can be configured in 2 
different flavours:

*HTTP Bearer Token Authentication*

auth.bearerToken
:	shared secret configured on all replication endpoints. 

*HTTP Basic Authentication*

remote.NAME.username
:	Username to use for HTTP authentication on this remote, if not
	given in the URL.

remote.NAME.password
:	Password to use for HTTP authentication on this remote.

In both cases, the Global Capability `Access Database` [1] needs to be allowed
in order to permit all `All-Users`' refs to be replicated. When _basic auth_ is
used, the capability must be assigned to the `remote.NAME.username` used in
configuration, whilst for _bearer token_, it needs to be assigned to
the `Pull-replication Internal User` user.

[1] https://gerrit-review.googlesource.com/Documentation/access-control.html#capability_accessDatabase

File `~/.ssh/config`
--------------------

If present, Gerrit reads and caches `~/.ssh/config` at startup, and
supports most SSH configuration options.  For example:

```
  Host host-one.example.com
    IdentityFile ~/.ssh/id_hostone
    PreferredAuthentications publickey

  Host mirror*.us.some.org
    User mirror-updater
    IdentityFile ~/.ssh/id_pubmirror
    PreferredAuthentications publickey
```

Supported options:

  * Host
  * Hostname
  * User
  * Port
  * IdentityFile
  * PreferredAuthentications
  * StrictHostKeyChecking

SSH authentication must be by passwordless public key, as there is no
facility to read passphrases on startup or passwords during the SSH
connection setup, and SSH agents are not supported from Java.

Host keys for any destination SSH servers must appear in the user's
`~/.ssh/known_hosts` file, and must be added in advance, before Gerrit
starts.  If a host key is not listed, Gerrit will be unable to connect
to that destination, and replication to that URL will fail.
