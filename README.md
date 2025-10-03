# DEPRECATION NOTICE

GerritForge has decided to [change the license to BSL](https://gitenterprise.me/2025/09/30/re-licensing-gerritforge-plugins-welcome-to-gerrit-enterprise/)
therefore the Apache 2.0 version of this plugin is deprecated.
The recommended version of the pull-replication plugin is on [GitHub](https://github.com/GerritForge/pull-replication)
and the development continues on [GerritHub.io](https://review.gerrithub.io/admin/repos/GerritForge/pull-replication,general).

# Gerrit pull-replication plugin (DEPRECATED)

This plugin can automatically mirror repositories from other systems.

Overview
--------

Typically replication should be done over SSH, with a passwordless
public/private key pair. On a trusted network it is also possible to
use replication over the insecure (but much faster due to no
authentication overhead or encryption) git:// protocol, by enabling
the `upload-pack` service on the receiving system, but this
configuration is not recommended. It is also possible to specify a
local path as replication source. This makes sense if a network
share is mounted to which the repositories should be replicated from.

## Access


To be allowed to trigger pull replication a user must be a member of a
group that is granted the 'Pull Replication' capability (provided
by this plugin) or the 'Administrate Server' capability.

## Change Indexing


Changes will be automatically indexed upon replication.


For more information please refer to the [docs](src/main/resources/Documentation)


