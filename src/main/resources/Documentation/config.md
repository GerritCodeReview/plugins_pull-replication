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
Next, create `$site_path/etc/@PLUGIN@.config` as a Git-style config
file, for example to replicate in parallel from four different hosts:</a>

```
  [remote "host-one"]
    url = gerrit2@host-one.example.com:/some/path/${name}.git

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

Then reload the replication plugin to pick up the new configuration:

```
  ssh -p 29418 localhost gerrit plugin reload @PLUGIN@
```

To manually trigger replication at runtime, see
SSH command [start](cmd-start.md).

File `@PLUGIN@.config`
-------------------------

The optional file `$site_path/etc/@PLUGIN@.config` is a Git-style
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

	By default, fetchs are retried indefinitely.

remote.NAME.url
:	Address of the remote server to fetch from.  Multiple URLs may be
	specified within a single remote block, listing different
	destinations which share the same settings.  Assuming
	sufficient threads in the thread pool, Gerrit fetchs from all
	URLs in parallel, using one thread per URL.

    TODO: This doesn't apply here: you can only replicate from one url

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

remote.NAME.threads
:	Number of worker threads to dedicate to fetching to the
	repositories described by this remote.  Each thread can fetch
	one project at a time, from one source URL.  Scheduling
	within the thread pool is done on a per-project basis.  If a
	remote block describes 4 URLs, allocating 4 threads in the
	pool will permit some level of parallel fetching.

	By default, 1 thread.

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

File `secure.config`
--------------------

The optional file `$site_path/secure.config` is a Git-style config
file that provides secure values that should not be world-readable,
such as passwords. Passwords for HTTP remotes can be obtained from
this file.

remote.NAME.username
:	Username to use for HTTP authentication on this remote, if not
	given in the URL.

remote.NAME.password
:	Password to use for HTTP authentication on this remote.

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
