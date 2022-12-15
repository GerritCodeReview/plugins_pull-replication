FROM gerritcodereview/gerrit:3.5.4-almalinux8

USER root

RUN yum update -y &&\
    yum install -y gettext &&\
    mkdir -p /var/gerrit/etc/replication

ARG JAVA_OPTS='--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED'
ARG GERRIT_BRANCH=stable-3.5
ARG LAST_BUILD=lastSuccessfulBuild/artifact/bazel-bin/plugins

RUN  java $JAVA_OPTS -jar /var/gerrit/bin/gerrit.war init --batch --install-all-plugins -d /var/gerrit && \
    java $JAVA_OPTS -jar /var/gerrit/bin/gerrit.war reindex -d /var/gerrit &&\
    curl -o /var/gerrit/plugins/healthcheck.jar https://gerrit-ci.gerritforge.com/view/Plugins-${GERRIT_BRANCH}/job/plugin-healthcheck-bazel-master/$LAST_BUILD/healthcheck/healthcheck.jar 
    # &&\
    # curl -o /var/gerrit/plugins/pull-replication.jar https://gerrit-ci.gerritforge.com/view/Plugins-${GERRIT_BRANCH}/job/plugin-pull-replication-bazel-master-stable-3.5/$LAST_BUILD/pull-replication/pull-replication.jar

RUN git config -f /var/gerrit/etc/secret.config --add auth.bearerToken "theSecretBearerToken"

COPY --chown=gerrit:gerrit entrypoint.sh /tmp/
RUN chmod +x /tmp/entrypoint.sh
COPY --chown=gerrit:gerrit configs/replication.config.template /var/gerrit/etc/
COPY --chown=gerrit:gerrit configs/gerrit.config.template /var/gerrit/etc/
COPY --chown=gerrit:gerrit configs/healthcheck.config /var/gerrit/etc/
COPY --chown=gerrit:gerrit configs/primary.config /tmp
COPY --chown=gerrit:gerrit configs/replica-1.config /tmp

### XXX
COPY --chown=gerrit:gerrit pull-replication.jar /var/gerrit/plugins/
COPY --chown=gerrit:gerrit pull-replication.jar /var/gerrit/lib/

ENTRYPOINT [ "/tmp/entrypoint.sh" ]