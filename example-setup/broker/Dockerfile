FROM gerritcodereview/gerrit:3.7.2-almalinux8

USER root

RUN yum install -y gettext

RUN git config -f /var/gerrit/etc/secure.config --add auth.bearerToken "theSecretBearerToken"

COPY --chown=gerrit:gerrit pull-replication.jar /var/gerrit/plugins/pull-replication.jar

COPY --chown=gerrit:gerrit events-kafka.jar /var/gerrit/plugins/events-kafka.jar
COPY --chown=gerrit:gerrit events-broker.jar /var/gerrit/lib/events-broker.jar

COPY --chown=gerrit:gerrit entrypoint.sh /tmp/
COPY --chown=gerrit:gerrit configs/replication.config.template /var/gerrit/etc/
COPY --chown=gerrit:gerrit configs/gerrit.config.template /var/gerrit/etc/

ENTRYPOINT [ "/tmp/entrypoint.sh" ]
