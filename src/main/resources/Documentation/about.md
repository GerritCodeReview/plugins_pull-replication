This plugin can automatically mirror repositories from other systems.

Typically replication should be done over SSH, with a passwordless
public/private key pair.  On a trusted network it is also possible to
use replication over the insecure (but much faster due to no
authentication overhead or encryption) git:// protocol, by enabling
the `upload-pack` service on the receiving system, but this
configuration is not recommended.  It is also possible to specify a
local path as replication source. This makes e.g. sense if a network
share is mounted to which the repositories should be replicated from.
