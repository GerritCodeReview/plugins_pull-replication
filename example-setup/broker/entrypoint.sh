#!/bin/bash -x

function setup_replication_config {
  if "$REPLICA" '==' 'true'
  then
    REPLICATION_CONFIG_TEMPLATE=replication.config.replica.template
  else
    REPLICATION_CONFIG_TEMPLATE=replication.config.primary.template
  fi

  echo "Replacing variables for file /var/gerrit/etc/$REPLICATION_CONFIG_TEMPLATE"
  cat /var/gerrit/etc/$REPLICATION_CONFIG_TEMPLATE | envsubst | sed 's/#{name}#/${name}/g' > /var/gerrit/etc/replication.config

  cat /var/gerrit/etc/replication.config
}

function setup_gerrit_config {
  echo "Replacing variables for file /var/gerrit/etc/gerrit.config.template"
  cat /var/gerrit/etc/gerrit.config.template | envsubst > /var/gerrit/etc/gerrit.config
}

JAVA_OPTS='--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED'

echo "Init phase ..."
java $JAVA_OPTS -jar /var/gerrit/bin/gerrit.war init --batch --install-all-plugins -d /var/gerrit

setup_replication_config
setup_gerrit_config

echo "Reindexing phase ..."
java $JAVA_OPTS -jar /var/gerrit/bin/gerrit.war reindex -d /var/gerrit

echo "Running Gerrit ..."
exec /var/gerrit/bin/gerrit.sh run
