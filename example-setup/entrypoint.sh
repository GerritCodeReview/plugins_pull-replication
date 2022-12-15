#!/bin/bash -x

ls -lrt /var/gerrit/etc
ls -lrt /var/gerrit/git

function setup_replication_config {

  echo "Replacing variables for file /var/gerrit/etc/replication.config.template"
  cat /var/gerrit/etc/replication.config.template | envsubst | sed 's/#{name}#/${name}/g' > /var/gerrit/etc/replication.config

  cat /var/gerrit/etc/replication.config
}

function setup_gerrit_config {
  echo "Replacing variables for file /var/gerrit/etc/gerrit.config.template"
  cat /var/gerrit/etc/gerrit.config.template | envsubst > /var/gerrit/etc/gerrit.config
}

setup_replication_config
setup_gerrit_config

if [[ $REPLICA == "true" ]]; then
  cp /tmp/primary.config /var/gerrit/etc/replication/
else
  cp /tmp/replica-1.config /var/gerrit/etc/replication/
fi

echo "Running Gerrit ..."
exec /var/gerrit/bin/gerrit.sh run