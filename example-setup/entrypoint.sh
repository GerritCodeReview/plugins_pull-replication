#!/bin/bash -x

function setup_replication_config {

  if $REPLICA;
  then
    echo "Replacing variables for file /var/gerrit/etc/replication.config.template"
    cat /var/gerrit/etc/replication-replica.config.template | envsubst | sed 's/#{name}#/${name}/g' > /var/gerrit/etc/replication.config
    cat /var/gerrit/etc/replication.config
  else
    echo "Replacing variables for file /var/gerrit/etc/replication.config.template"
    cat /var/gerrit/etc/replication-primary.config.template | envsubst | sed 's/#{name}#/${name}/g' > /var/gerrit/etc/replication.config
    cat /var/gerrit/etc/replication.config
  fi

}

function setup_gerrit_config {
  echo "Replacing variables for file /var/gerrit/etc/gerrit.config.template"
  cat /var/gerrit/etc/gerrit.config.template | envsubst > /var/gerrit/etc/gerrit.config
}

setup_replication_config
setup_gerrit_config

echo "Init phase..."
java -jar /var/gerrit/bin/gerrit.war init --no-auto-start --batch --install-all-plugins -d /var/gerrit

echo "Grant access database to the pull-replication internal user ..."
mkdir /tmp/gerrit-post-init && pushd /tmp/gerrit-post-init
git clone /var/gerrit/git/All-Projects && cd All-Projects && git fetch origin refs/meta/config && git checkout FETCH_HEAD
git config -f project.config capability.accessDatabase "group Pull-replication Internal User"
echo -e "pullreplication:internal-user\tPull-replication Internal User" >> groups
git config user.name gerrit
git config user.email gerrit@localhost
git add project.config groups
git commit -m 'Added global-capability to pull-replication internal user'
git push origin HEAD:refs/meta/config
popd
rm -Rf /tmp/gerrit-post-init

echo "Running Gerrit ..."
exec /var/gerrit/bin/gerrit.sh run