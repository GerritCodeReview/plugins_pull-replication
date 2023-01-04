# What is this for?

This docker compose sets up primary and replica nodes using pull-replication to
replicate over kafka.

Copy the pull-replication, events-kafka, and events-broker artefacts to test
into this directory:

```bash
cp $GERRIT_HOME/bazel-bin/plugins/pull-replication/pull-replication.jar .
cp $GERRIT_HOME/bazel-bin/plugins/events-kafka/events-kafka.jar .
cp $GERRIT_HOME/bazel-bin/plugins/events-broker/libevents-broker.jar .
```

Start up the application using docker compose:

```bash
docker-compose up
```