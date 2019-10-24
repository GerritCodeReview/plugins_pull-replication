@PLUGIN@ list
==============

NAME
----
@PLUGIN@ list - List remote destination information.

SYNOPSIS
--------
```
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list
  [--remote <PATTERN>]
  [--detail]
  [--json]
```

DESCRIPTION
-----------
Lists the name and URL for remote sources.

ACCESS
------
Caller must be a member of the privileged 'Administrators' group.

SCRIPTING
---------
This command is intended to be used in scripts.

OPTIONS
-------

`--remote <PATTERN>`
:	Only print information for sources whose remote name matches
	the `PATTERN`.

`--detail`
:	Print additional detailed information: AdminUrl, AuthGroup, Project
	and queue (pending and in-flight).

`--json`
:	Output in json format.

EXAMPLES
--------
List all sources:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list
```

List all sources detail information:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --detail
```

List all sources detail information in json format:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --detail --json
```

List sources whose name contains mirror:

```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --remote mirror
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ list --remote ^.*mirror.*
```

SEE ALSO
--------

* [Replication Configuration](config.md)
* [Access Control](../../../Documentation/access-control.html)
