@PLUGIN@ start
==============

NAME
----
@PLUGIN@ start - Manually trigger replication, to recover a node

SYNOPSIS
--------
```
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start
  [--now]
  [--wait]
  [--url <PATTERN>]
  {--all | <PROJECT PATTERN> ...}
```

DESCRIPTION
-----------
Schedules pull replication of the specified projects to all configured
replication sources, or only those whose URLs match the pattern
given on the command line.

If you get message "Nothing to replicate" while running this command,
it may be caused by several reasons, such as you give a wrong url
pattern in command options, or the authGroup in the @PLUGIN@.config
has no read access for the replicated projects.

If one or several project patterns are supplied, only those projects
conforming to both this/these pattern(s) and those defined in
@PLUGIN@.config for the target host(s) are queued for replication.

The patterns follow the same format as those in @PLUGIN@.config,
where wildcard or regular expression patterns can be given.
Regular expression patterns must match a complete project name to be
considered a match.

A regular expression pattern starts with `^` and a wildcard pattern ends
with a `*`. If the pattern starts with `^` and ends with `*`, it is
treated as a regular expression.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group,
or have been granted the 'Start Replication' plugin-owned capability.

SCRIPTING
---------
This command is intended to be used in scripts.

OPTIONS
-------

`--now`
:   Start replicating right away without waiting the per remote
	replication delay.

`--wait`
:	Wait for replication to finish before exiting.

`--all`
:	Schedule replication for all projects.

`--url <PATTERN>`
:	Replicate only from replication sources whose URL contains
	the substring `PATTERN`.  This can be useful to replicate
	only from a previously down node, which has been brought back
	online.

EXAMPLES
--------
Replicate every project, from every configured remote:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start --all
```

Replicate only from `srv2` now that it is back online:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start --url srv2 --all
```

Replicate only projects located in the `documentation` subdirectory:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start documentation/*
```

Replicate projects whose path includes a folder named `vendor` to host slave1:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ start --url slave1 ^(|.*/)vendor(|/.*)
```

SEE ALSO
--------

* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
