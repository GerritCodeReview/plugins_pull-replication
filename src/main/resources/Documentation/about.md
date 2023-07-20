This plugin can automatically mirror repositories from other systems.

Typically replication should be done over SSH, with a passwordless
public/private key pair.  On a trusted network it is also possible to
use replication over the insecure (but much faster due to no
authentication overhead or encryption) git:// protocol, by enabling
the `upload-pack` service on the receiving system, but this
configuration is not recommended.  It is also possible to specify a
local path as replication source. This makes e.g. sense if a network
share is mounted to which the repositories should be replicated from.

Access
------

To be allowed to trigger pull replication a user must be a member of a
group that is granted the 'Pull Replication' capability (provided
by this plugin) or the 'Administrate Server' capability.

When replicating hidden projects, the pull replication user needs to have
the 'Administrate Server' capability or being added as the owner of each
individual project that is supposed to be replicated.

Change Indexing
--------

Changes will be automatically indexed upon replication.
