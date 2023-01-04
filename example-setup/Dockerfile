FROM gerritcodereview/gerrit:3.5.4-almalinux8

USER root

RUN yum install -y gettext

ARG JAVA_OPTS='--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED'

RUN  java $JAVA_OPTS -jar /var/gerrit/bin/gerrit.war init --batch --install-all-plugins -d /var/gerrit && \
    java $JAVA_OPTS -jar /var/gerrit/bin/gerrit.war reindex -d /var/gerrit

RUN git config -f /var/gerrit/etc/secure.config --add auth.bearerToken "theSecretBearerToken"

COPY --chown=gerrit:gerrit pull-replication.jar /var/gerrit/plugins/pull-replication.jar
COPY --chown=gerrit:gerrit pull-replication.jar /var/gerrit/lib/pull-replication.jar
COPY --chown=gerrit:gerrit entrypoint.sh /tmp/
RUN chmod +x /tmp/entrypoint.sh
COPY --chown=gerrit:gerrit configs/replication.config.template /var/gerrit/etc/
COPY --chown=gerrit:gerrit configs/gerrit.config.template /var/gerrit/etc/

ENTRYPOINT [ "/tmp/entrypoint.sh" ]
