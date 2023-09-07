FROM gerritcodereview/gerrit:3.8.1-almalinux9

USER root

RUN yum install -y gettext

RUN git config -f /var/gerrit/etc/secure.config --add auth.bearerToken "theSecretBearerToken"

COPY --chown=gerrit:gerrit pull-replication.jar /var/gerrit/plugins/pull-replication.jar

# The message-broker notification needs to be the last in the notification chain
# hence rename it with a 'z-' prefix because the Gerrit plugin loader starts the
# plugins in filename alphabetical order.
COPY --chown=gerrit:gerrit events-kafka.jar /var/gerrit/plugins/z-events-kafka.jar
COPY --chown=gerrit:gerrit events-broker.jar /var/gerrit/lib/events-broker.jar

COPY --chown=gerrit:gerrit entrypoint.sh /tmp/
COPY --chown=gerrit:gerrit configs/replication.config.template /var/gerrit/etc/
COPY --chown=gerrit:gerrit configs/gerrit.config.template /var/gerrit/etc/

ENTRYPOINT [ "/tmp/entrypoint.sh" ]
