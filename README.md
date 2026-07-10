# Gerrit pull-replication plugin (Apache 2.0 fork)

> **Fork notice:** GerritForge [relicensed their plugins to BSL](https://gitenterprise.me/2025/09/30/re-licensing-gerritforge-plugins-welcome-to-gerrit-enterprise/)
> in September 2025. This repository is an independently maintained fork under the
> original Apache 2.0 license. The BSL versions are on
> [GitHub](https://github.com/GerritForge/pull-replication) /
> [GerritHub](https://review.gerrithub.io/admin/repos/GerritForge/pull-replication,general).

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


