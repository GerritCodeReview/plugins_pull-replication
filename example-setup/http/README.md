# What is this for?

This docker compose set up a primary replica using pull-replication to replicate the data
over git http across the 2 nodes.

To spin up your environment:
1) copy the pull-replication artefact to test in te example-setup directory:

```bash
cp $GERRIT_HOME/bazel-bin/plugins/pull-replication/pull-replication.jar .
```

2) spin up the docker compose

```bash
docker-compose up
```