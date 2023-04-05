#!/bin/bash -x

function setup_replication_config {

  echo "Replacing variables for file /var/gerrit/etc/replication.config.template"
  cat /var/gerrit/etc/replication.config.template | envsubst | sed 's/#{name}#/${name}/g' > /var/gerrit/etc/replication.config

  cat /var/gerrit/etc/replication.config
}

function setup_gerrit_config {
  echo "Replacing variables for file /var/gerrit/etc/gerrit.config.template"
  cat /var/gerrit/etc/gerrit.config.template | envsubst > /var/gerrit/etc/gerrit.config
}

function setup_zookeeper_config {
  echo "Replacing variables for file /var/gerrit/etc/zookeeper-refdb.config.template" 
  cat /var/gerrit/etc/zookeeper-refdb.config.template | envsubst > /var/gerrit/etc/zookeeper-refdb.config
}

setup_replication_config
setup_zookeeper_config
setup_gerrit_config

ARG JAVA_OPTS='--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED'

echo "Init phase ..."
java $JAVA_OPTS -jar /var/gerrit/bin/gerrit.war init --batch --install-all-plugins -d /var/gerrit

echo "Reindexing phase ..."
java $JAVA_OPTS -jar /var/gerrit/bin/gerrit.war reindex -d /var/gerrit

echo "Running Gerrit ..."
exec /var/gerrit/bin/gerrit.sh run
