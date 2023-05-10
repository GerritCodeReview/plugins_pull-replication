# What is this for?

This docker compose sets up primary and replica nodes using pull-replication to
replicate with notifications over a broker. In this case our broker
implementation is Kafka.

Copy the pull-replication, events-kafka, and events-broker artefacts to test
into this directory:

```bash
cp $GERRIT_HOME/bazel-bin/plugins/pull-replication/pull-replication.jar .
cp $GERRIT_HOME/bazel-bin/plugins/events-kafka/events-kafka.jar .
cp $GERRIT_HOME/bazel-bin/plugins/events-broker/events-broker.jar .
```

Start up the application using docker compose:

```bash
docker-compose up
```
