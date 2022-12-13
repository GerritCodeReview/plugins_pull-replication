#!/bin/bash -x

# Copyright (C) 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCATION="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
# LOCAL_ENV="$( cd "${LOCATION}/../setup_local_env" >/dev/null 2>&1 && pwd )"
LOCAL_ENV=${LOCATION}
GERRIT_BRANCH=stable-3.5
GERRIT_CI=https://gerrit-ci.gerritforge.com/view/Plugins-$GERRIT_BRANCH/job
LAST_BUILD=lastSuccessfulBuild/artifact/bazel-bin/plugins
DEF_GERRIT_IMAGE=3.5.4-almalinux8
DEF_GERRIT_HEALTHCHECK_START_PERIOD=60s
DEF_GERRIT_HEALTHCHECK_INTERVAL=5s
DEF_GERRIT_HEALTHCHECK_TIMEOUT=5s
DEF_GERRIT_HEALTHCHECK_RETRIES=5

function check_application_requirements {
  type java >/dev/null 2>&1 || { echo >&2 "Require java but it's not installed. Aborting."; exit 1; }
  type docker >/dev/null 2>&1 || { echo >&2 "Require docker but it's not installed. Aborting."; exit 1; }
  type docker-compose >/dev/null 2>&1 || { echo >&2 "Require docker-compose but it's not installed. Aborting."; exit 1; }
  type wget >/dev/null 2>&1 || { echo >&2 "Require wget but it's not installed. Aborting."; exit 1; }
  type envsubst >/dev/null 2>&1 || { echo >&2 "Require envsubst but it's not installed. Aborting."; exit 1; }
  type openssl >/dev/null 2>&1 || { echo >&2 "Require openssl but it's not installed. Aborting."; exit 1; }
  type git >/dev/null 2>&1 || { echo >&2 "Require git but it's not installed. Aborting."; exit 1; }
}

function setup_replication_config {
  SOURCE_REPLICATION_CONFIG=${LOCAL_ENV}/configs/replication.config
  DESTINATION_REPLICATION_CONFIG=$1

  export REPLICATION_URL="url = $2"
  export REPLICATION_DELAY_SEC=1

  echo "Replacing variables for file ${SOURCE_REPLICATION_CONFIG} and copying to ${DESTINATION_REPLICATION_CONFIG}"
  cat $SOURCE_REPLICATION_CONFIG | envsubst | sed 's/#{name}#/${name}/g' > $DESTINATION_REPLICATION_CONFIG
}

function setup_gerrit_config {
  SOURCE_RGERRIT_CONFIG=${LOCAL_ENV}/configs/gerrit.config
  DESTINATION_GERRIT_CONFIG=$1

  export INSTANCE_ID=$4
  export SSH_ADVERTISED_PORT=$5
  export LOCATION_TEST_SITE=/var/gerrit
  export REMOTE_DEBUG_PORT=5005
  export GERRIT_SSHD_PORT=29418
  export HTTP_PROTOCOL=http
  export GERRIT_HTTPD_PORT=8080

  echo "Replacing variables for file ${SOURCE_RGERRIT_CONFIG} and copying to ${DESTINATION_GERRIT_CONFIG}"
  cat $SOURCE_RGERRIT_CONFIG | envsubst | sed 's/#{name}#/${name}/g' > $DESTINATION_GERRIT_CONFIG

  # set plugins for multi-site as mandatory so that gerrit will not start if they are not loaded
  declare -a MANDATORY_PLUGINS=(replication)
  for plugin in "${MANDATORY_PLUGINS[@]}"
  do
    git config --file $DESTINATION_GERRIT_CONFIG --add plugins.mandatory "${plugin}"
  done
}

function cleanup_tests_hook {
  echo "Shutting down the setup"
  docker-compose -f ${DEPLOYMENT_LOCATION}/docker-compose.yaml down -v
  echo "Removing setup dir"
  rm -rf ${DEPLOYMENT_LOCATION}
}

function copy_files_in_dir {
  ORIGIN_DIR=$1
  DESTINATION_DIR=$2
  EXTENSION=$3

  for f in ${ORIGIN_DIR}${EXTENSION}; do
    echo "Copying ${f} to ${DESTINATION_DIR}..."
    docker cp $f "${DESTINATION_DIR}";
  done
}

# Check application requirements
check_application_requirements

while [ $# -ne 0 ]
do
case "$1" in
  "--help" )
    echo "Usage: sh $0 [--option $value]"
    echo
    echo "[--gerrit-image]                Gerrit docker image to be used for testing; defaults to [${DEF_GERRIT_IMAGE}]"
    echo "[--location]                    Directory in which this script resides. Needed only when called from 'bazel test'."
    echo "[--local-env]                   'setup_local_env' directory location. Needed only when called from 'bazel test'."
    echo
    exit 0
  ;;
  "--gerrit-image" )
    GERRIT_IMAGE=$2
    shift
    shift
  ;;
  "--location" )
    LOCATION=$2
    shift
    shift
  ;;
  "--local-env" )
    LOCAL_ENV=$2
    shift
    shift
  ;;
  *     )
    echo "Unknown option argument: $1"
    shift
    shift
  ;;
esac
done

# Defaults
DEPLOYMENT_LOCATION=$(mktemp -d || $(echo >&2 "Could not create temp dir" && exit 1))
MULTISITE_LIB_LOCATION=${MULTISITE_LIB_LOCATION:-${DEF_MULTISITE_LOCATION}}
GERRIT_IMAGE=${GERRIT_IMAGE:-${DEF_GERRIT_IMAGE}}

# Gerrit primary
GERRIT_1_ETC=${DEPLOYMENT_LOCATION}/etc_1
GERRIT_1_PLUGINS=${DEPLOYMENT_LOCATION}/plugins_1
GERRIT_1_LIBS=${DEPLOYMENT_LOCATION}/libs_1

# Gerrit secondary
GERRIT_2_ETC=${DEPLOYMENT_LOCATION}/etc_2
GERRIT_2_PLUGINS=${DEPLOYMENT_LOCATION}/plugins_2
GERRIT_2_LIBS=${DEPLOYMENT_LOCATION}/libs_2

echo "Deployment location: [${DEPLOYMENT_LOCATION}]"

echo "Downloading common plugins"
COMMON_PLUGINS=${DEPLOYMENT_LOCATION}/common_plugins
mkdir -p ${COMMON_PLUGINS}

echo "Downloading healthcheck plugin $GERRIT_BRANCH"
wget $GERRIT_CI/plugin-healthcheck-bazel-master/$LAST_BUILD/healthcheck/healthcheck.jar \
  -O $COMMON_PLUGINS/healthcheck.jar || { echo >&2 "Cannot download healthcheck plugin: Check internet connection. Aborting"; exit 1; }

echo "Downloading common libs"
COMMON_LIBS=${DEPLOYMENT_LOCATION}/common_libs
mkdir -p ${COMMON_LIBS}

echo "Getting replication.jar as a library"
CONTAINER_NAME=$(docker create -ti --entrypoint /bin/bash gerritcodereview/gerrit:"${GERRIT_IMAGE}") && \
echo "* PONCH Copied replication..." \
docker cp ${CONTAINER_NAME}:/var/gerrit/plugins/replication.jar $COMMON_LIBS/
echo "* PONCH Copying stuff"
docker rm -fv ${CONTAINER_NAME}

echo "Setting up directories"
mkdir -p ${GERRIT_1_ETC} ${GERRIT_1_PLUGINS} ${GERRIT_1_LIBS} ${GERRIT_2_ETC} ${GERRIT_2_PLUGINS} ${GERRIT_2_LIBS}

echo "Copying plugins"
cp -f $COMMON_PLUGINS/* ${GERRIT_1_PLUGINS}
cp -f $COMMON_PLUGINS/* ${GERRIT_2_PLUGINS}

echo "Copying libs"
ls -lrt ${DEPLOYMENT_LOCATION}/common_libs
echo "PONCH: ls -lrt ${DEPLOYMENT_LOCATION}/common_libs"
cp -f $COMMON_LIBS/* ${GERRIT_1_LIBS}
cp -f $COMMON_LIBS/* ${GERRIT_2_LIBS}

echo "Setting up configuration"
echo "Setup healthcheck config"
cp -f ${LOCATION}/configs/healthcheck.config $GERRIT_1_ETC
cp -f ${LOCATION}/configs/healthcheck.config $GERRIT_2_ETC

echo "Setup replication config"
setup_replication_config "${GERRIT_1_ETC}/replication.config" 'file:///var/gerrit/git-instance2/${name}.git'
setup_replication_config "${GERRIT_2_ETC}/replication.config" 'file:///var/gerrit/git-instance1/${name}.git'

echo "Setup gerrit config"
setup_gerrit_config "${GERRIT_1_ETC}/gerrit.config" $BROKER_HOST $BROKER_PORT instance-1 29418
setup_gerrit_config "${GERRIT_2_ETC}/gerrit.config" $BROKER_HOST $BROKER_PORT instance-2 29419

echo "Generating common SSH key for tests"
COMMON_SSH=${DEPLOYMENT_LOCATION}/common_ssh
mkdir -p ${COMMON_SSH}
ssh-keygen -b 2048 -t rsa -f ${COMMON_SSH}/id_rsa -q -N "" || { echo >&2 "Cannot generate common SSH keys. Aborting"; exit 1; }

echo "Starting containers"
COMPOSE_FILES="-f ${LOCATION}/docker-compose.yaml"

# store setup in single file (under ${DEPLOYMENT_LOCATION}) with all variables resolved
export GERRIT_IMAGE; \
  docker-compose ${COMPOSE_FILES} config > ${DEPLOYMENT_LOCATION}/docker-compose.yaml

# trap cleanup_tests_hook EXIT
docker-compose -f ${DEPLOYMENT_LOCATION}/docker-compose.yaml ps -a
docker-compose -f ${DEPLOYMENT_LOCATION}/docker-compose.yaml logs -f --no-color -t > ${DEPLOYMENT_LOCATION}/site.log &

docker-compose -f ${DEPLOYMENT_LOCATION}/docker-compose.yaml up --no-start gerrit1 gerrit2
docker-compose -f ${DEPLOYMENT_LOCATION}/docker-compose.yaml ps -a
GERRIT1_CONTAINER=$(docker-compose -f ${DEPLOYMENT_LOCATION}/docker-compose.yaml ps -q gerrit1)
GERRIT2_CONTAINER=$(docker-compose -f ${DEPLOYMENT_LOCATION}/docker-compose.yaml ps -q gerrit2)

#copy files to gerrit containers
echo "Copying files to Gerrit containers"
copy_files_in_dir "${GERRIT_1_ETC}/" "${GERRIT1_CONTAINER}:/var/gerrit/etc" *.config
copy_files_in_dir "${GERRIT_1_PLUGINS}/" "${GERRIT1_CONTAINER}:/var/gerrit/plugins" *.jar
copy_files_in_dir "${GERRIT_1_LIBS}/" "${GERRIT1_CONTAINER}:/var/gerrit/libs" *.jar
copy_files_in_dir "${COMMON_SSH}/" "${GERRIT1_CONTAINER}:/var/gerrit/.ssh" id_rsa

copy_files_in_dir "${GERRIT_2_ETC}/" "${GERRIT2_CONTAINER}:/var/gerrit/etc" *.config
copy_files_in_dir "${GERRIT_2_PLUGINS}/" "${GERRIT2_CONTAINER}:/var/gerrit/plugins" *.jar
copy_files_in_dir "${GERRIT_2_LIBS}/" "${GERRIT2_CONTAINER}:/var/gerrit/libs" *.jar
copy_files_in_dir "${COMMON_SSH}/" "${GERRIT2_CONTAINER}:/var/gerrit/.ssh" id_rsa

echo "Starting Gerrit servers"
docker-compose -f ${DEPLOYMENT_LOCATION}/docker-compose.yaml up -d gerrit1 gerrit2

echo "Waiting for services to start (and being healthy) and calling e2e tests"
docker-compose -f ${DEPLOYMENT_LOCATION}/docker-compose.yaml ps -a
