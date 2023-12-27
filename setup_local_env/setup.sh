#!/bin/bash

# Copyright (C) 2019 The Android Open Source Project
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


SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
GERRIT_BRANCH=stable-3.9
GERRIT_CI=https://gerrit-ci.gerritforge.com/view/Plugins-$GERRIT_BRANCH/job
LAST_BUILD=lastSuccessfulBuild/artifact/bazel-bin/plugins

function check_application_requirements {
  type java >/dev/null 2>&1 || { echo >&2 "Require java but it's not installed. Aborting."; exit 1; }
  type docker >/dev/null 2>&1 || { echo >&2 "Require docker but it's not installed. Aborting."; exit 1; }
  [ $($SUDO docker --version | awk '{print $3}' | cut -d '.' -f 1) -ge 20 ] || { echo >&2 "Require docker v20 or later. Aborting."; exit 1; }
  type wget >/dev/null 2>&1 || { echo >&2 "Require wget but it's not installed. Aborting."; exit 1; }
  type envsubst >/dev/null 2>&1 || { echo >&2 "Require envsubst but it's not installed. Aborting."; exit 1; }
  type openssl >/dev/null 2>&1 || { echo >&2 "Require openssl but it's not installed. Aborting."; exit 1; }
}

function get_pull_replication_api_url {
  REPLICATION_HOSTNAME=$1

  echo "apiUrl = http://$REPLICATION_HOSTNAME:$REPLICATION_HTTPD_PORT"
}

function get_pull_replication_url {
  REPLICATION_HOSTNAME=$1

  echo "url = http://$REPLICATION_HOSTNAME:$REPLICATION_HTTPD_PORT/#{name}#.git"
}

function deploy_tls_certificates {
  echo "Deplying certificates in $HA_PROXY_CERTIFICATES_DIR..."
  openssl req -new -newkey rsa:2048 -x509 -sha256 -days 365 -nodes \
  -out $HA_PROXY_CERTIFICATES_DIR/MyCertificate.crt \
  -keyout $HA_PROXY_CERTIFICATES_DIR/GerritLocalKey.key \
  -subj "/C=GB/ST=London/L=London/O=Gerrit Org/OU=IT Department/CN=localhost"
  cat $HA_PROXY_CERTIFICATES_DIR/MyCertificate.crt $HA_PROXY_CERTIFICATES_DIR/GerritLocalKey.key | tee $HA_PROXY_CERTIFICATES_DIR/GerritLocalKey.pem
}

function copy_config_files {
  for file in `ls $SCRIPT_DIR/configs/*.config`
  do
    file_name=`basename $file`

    CONFIG_TEST_SITE=$1
    export GERRIT_HTTPD_PORT=$2
    export LOCATION_TEST_SITE=$3
    export GERRIT_SSHD_PORT=$4
    export GERRIT_HOSTNAME=$5
    export REMOTE_DEBUG_PORT=$6
    export INSTANCE_ID=${7}
    export REPLICA_INSTANCE_ID=${8}

    echo "Replacing variables for file $file and copying to $CONFIG_TEST_SITE/$file_name"

    cat $file | envsubst | sed 's/#{name}#/${name}/g' > $CONFIG_TEST_SITE/$file_name
  done
}
function deploy_config_files {
  # broker configuration
  export BROKER_HOST=localhost

  # ZK configuration
  export ZK_HOST=localhost
  export ZK_PORT=2181

  # SITE 1
  GERRIT_SITE_HOSTNAME=$1
  GERRIT_SITE_HTTPD_PORT=$2
  GERRIT_SITE_SSHD_PORT=$3
  CONFIG_TEST_SITE=$LOCATION_TEST_SITE/etc
  GERRIT_SITE_REMOTE_DEBUG_PORT="5005"
  GERRIT_SITE_INSTANCE_ID="instance-1"

  # Set config SITE1
  copy_config_files $CONFIG_TEST_SITE $GERRIT_SITE_HTTPD_PORT $LOCATION_TEST_SITE $GERRIT_SITE_SSHD_PORT $GERRIT_SITE_HTTPD_PORT $GERRIT_SITE_HOSTNAME $GERRIT_SITE_HOSTNAME $GERRIT_SITE_REMOTE_DEBUG_PORT $GERRIT_SITE_INSTANCE_ID $GERRIT_SITE_INSTANCE_ID
}

function is_docker_desktop {
  echo $($SUDO docker info | grep "Operating System: Docker Desktop" | wc -l)
}

function docker_host_env {
  IS_DOCKER_DESKTOP=$(is_docker_desktop)
  if [ "$IS_DOCKER_DESKTOP" = "1" ];then
    echo "mac"
  else
    echo "linux"
  fi
}

function cleanup_environment {
  echo "Stopping $BROKER_TYPE docker container"
  printenv > "${SCRIPT_DIR}/docker-compose-${BROKER_TYPE}.env"
  $SUDO docker compose -f "${SCRIPT_DIR}/docker-compose-${BROKER_TYPE}.yaml" --env-file "${SCRIPT_DIR}/docker-compose-${BROKER_TYPE}.env" down 2> /dev/null

  echo "Stopping GERRIT instances"
  $1/bin/gerrit.sh stop 2> /dev/null

  echo "REMOVING setup directory $3"
  rm -rf $3 2> /dev/null
}

function cleanup_plugins {
  echo "Removing plugins from $DEPLOYMENT_LOCATION"
  rm -f $DEPLOYMENT_LOCATION/gerrit.war
  rm -f $DEPLOYMENT_LOCATION/events-broker.jar
  rm -f $DEPLOYMENT_LOCATION/global-refdb.jar
  rm -f $DEPLOYMENT_LOCATION/healthcheck.jar
  rm -f $DEPLOYMENT_LOCATION/events-kafka.jar
}


function check_if_container_is_running {
  local container=$1;
  echo $($SUDO docker inspect "$container" 2> /dev/null | grep '"Running": true' | wc -l)
}

function ensure_docker_compose_is_up_and_running {
  local log_label=$1
  local container_name=$2
  local docker_compose_file=$3

  local is_container_running=$(check_if_container_is_running "$container_name")
  if [ "$is_container_running" -lt 1 ];then
    printenv > "${SCRIPT_DIR}/${docker_compose_file}.env"
    echo "[$log_label] Starting docker containers"
    $SUDO docker compose -f "${SCRIPT_DIR}/${docker_compose_file}" --env-file "${SCRIPT_DIR}/${docker_compose_file}.env" up -d

    echo "[$log_label] Waiting for docker containers to start..."
    while [[ $(check_if_container_is_running "$container_name") -lt 1 ]];do sleep 10s; done
  else
    echo "[$log_label] Containers already running, nothing to do"
  fi
}

function prepare_broker_data {
  if [ "$BROKER_TYPE" = "kinesis" ]; then
    create_kinesis_streams
  fi
}

function download_artifact_from_ci {
  local artifact_name=$1
  local prefix=${2:-plugin}
  echo "Downloading $artifact_name $prefix $GERRIT_BRANCH"

  wget $GERRIT_CI/$prefix-$artifact_name-bazel-$GERRIT_BRANCH/$LAST_BUILD/$artifact_name/$artifact_name.jar \
  -O $DEPLOYMENT_LOCATION/$artifact_name.jar || \
  wget $GERRIT_CI/$prefix-$artifact_name-bazel-master-$GERRIT_BRANCH/$LAST_BUILD/$artifact_name/$artifact_name.jar \
  -O $DEPLOYMENT_LOCATION/$artifact_name.jar || \
  { echo >&2 "Cannot download $artifact_name $prefix: Check internet connection. Aborting"; exit 1; }
}

function help {
    echo "Usage: bash $0 [--option $value]"
    echo
    echo "[--release-war-file]            Location to release.war file"
    echo "[--eventsbroker-lib-file]       Location to lib events-broker.jar file"
    echo
    echo "[--new-deployment]              Cleans up previous gerrit deployment and re-installs it. default true"
    echo "[--deployment-location]         Base location for the test deployment; default /tmp"
    echo
    echo "[--gerrit-canonical-host]       The default host for Gerrit to be accessed through; default localhost"
    echo
    echo "[--gerrit-httpd-port]          Gerrit Instance http port; default 8080"
    echo "[--gerrit-sshd-port]           Gerrit Instance sshd port; default 29418"
    echo
    echo "[--replication-delay]           Replication delay across the two instances in seconds"
    echo
    echo "[--just-cleanup-env]            Cleans up previous deployment; default false"
    echo
    echo "[--enabled-https]               Enabled https; default true"
    echo
    echo "[--broker-type]                 events broker type; 'kafka', 'kinesis' or 'gcloud-pubsub'. Default 'kafka'"
    echo
    echo "[--sudo]                        run docker commands with sudo"
    echo
}


while [ $# -ne 0 ]
do
case "$1" in
  "--help" )
    help
    exit 0
  ;;
  "--new-deployment")
        NEW_INSTALLATION=$2
    shift
    shift
  ;;
  "--deployment-location" )
    DEPLOYMENT_LOCATION=$2
    shift
    shift
  ;;
  "--release-war-file" )
    RELEASE_WAR_FILE_LOCATION=$2
    shift
    shift
  ;;
  "--eventsbroker-lib-file" )
    EVENTS_BROKER_LIB_LOCATION=$2
    shift
    shift
  ;;
  "--gerrit-canonical-host" )
    export GERRIT_CANONICAL_HOSTNAME=$2
    shift
    shift
  ;;
  "--gerrit-httpd-port" )
         GERRIT_HTTPD_PORT=$2
    shift
    shift
  ;;
  "--gerrit-sshd-port" )
         GERRIT_SSHD_PORT=$2
    shift
    shift
  ;;
  "--just-cleanup-env" )
         JUST_CLEANUP_ENV=$2
    shift
    shift
  ;;
  "--enabled-https" )
    HTTPS_ENABLED=$2
    shift
    shift
  ;;
  "--sudo" )
    SUDO=sudo
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

# Check application requirements
check_application_requirements

# Defaults
NEW_INSTALLATION=${NEW_INSTALLATION:-"true"}
DOWNLOAD_WEBSESSION_PLUGIN=${DOWNLOAD_WEBSESSION_PLUGIN:-"true"}
DEPLOYMENT_LOCATION=${DEPLOYMENT_LOCATION:-"/tmp"}
GERRIT_HOSTNAME=${GERRIT_HOSTNAME:-"localhost"}
GERRIT_HTTPD_PORT=${GERRIT_HTTPD_PORT:-"8080"}
GERRIT_SSHD_PORT=${GERRIT_SSHD_PORT:-"29418"}
HTTPS_ENABLED=${HTTPS_ENABLED:-"false"}
BROKER_TYPE="kafka"

export COMMON_LOCATION=$DEPLOYMENT_LOCATION/gerrit_setup
LOCATION_TEST_SITE=$COMMON_LOCATION/instance

RELEASE_WAR_FILE_LOCATION=${RELEASE_WAR_FILE_LOCATION:-bazel-bin/release.war}
EVENTS_BROKER_LIB_LOCATION=${EVENTS_BROKER_LIB_LOCATION:-bazel-bin/plugins/events-broker/events-broker.jar}

export FAKE_NFS=$COMMON_LOCATION/fake_nfs

if [ "$JUST_CLEANUP_ENV" = "true" ];then
  cleanup_environment $LOCATION_TEST_SITE $COMMON_LOCATION
  cleanup_plugins
  exit 0
fi

if [ -z $RELEASE_WAR_FILE_LOCATION ];then
  echo "A release.war file is required. Usage: sh $0 --release-war-file /path/to/release.war"
  exit 1
else
  cp -f $RELEASE_WAR_FILE_LOCATION $DEPLOYMENT_LOCATION/gerrit.war >/dev/null 2>&1 || { echo >&2 "$RELEASE_WAR_FILE_LOCATION: Not able to copy the file. Aborting"; exit 1; }
fi

echo "Copying events-broker library"
cp -f $EVENTS_BROKER_LIB_LOCATION $DEPLOYMENT_LOCATION/events-broker.jar  >/dev/null 2>&1 || { echo >&2 "$EVENTS_BROKER_LIB_LOCATION: Not able to copy the file. Aborting"; exit 1; }

download_artifact_from_ci healthcheck
download_artifact_from_ci events-kafka

if [ "$HTTPS_ENABLED" = "true" ];then
  export HTTP_PROTOCOL="https"
  export GERRIT_CANONICAL_WEB_URL="$HTTP_PROTOCOL://$GERRIT_CANONICAL_HOSTNAME/"
  export HTTPS_BIND="bind *:443 ssl crt $HA_PROXY_CONFIG_DIR/certificates/GerritLocalKey.pem"
  HTTPS_CLONE_MSG="Using self-signed certificates, to clone via https - 'git config --global http.sslVerify false'"
else
  export HTTP_PROTOCOL="http"
  export GERRIT_CANONICAL_WEB_URL="$HTTP_PROTOCOL://$GERRIT_CANONICAL_HOSTNAME:$GERRIT_CANONICAL_PORT/"
fi

# New installation
if [ $NEW_INSTALLATION = "true" ]; then
  cleanup_environment $LOCATION_TEST_SITE $COMMON_LOCATION

  echo "Setting up directories"
  mkdir -p $LOCATION_TEST_SITE $HA_PROXY_CERTIFICATES_DIR $FAKE_NFS
  java -jar $DEPLOYMENT_LOCATION/gerrit.war init --batch --no-auto-start --install-all-plugins --dev -d $LOCATION_TEST_SITE

  # Deploying TLS certificates
  if [ "$HTTPS_ENABLED" = "true" ];then
    deploy_tls_certificates
  fi

  echo "Copy healthcheck plugin"
  cp -f $DEPLOYMENT_LOCATION/healthcheck.jar $LOCATION_TEST_SITE/plugins/healthcheck.jar

  echo "Copy events broker library"
  cp -f $DEPLOYMENT_LOCATION/events-broker.jar $LOCATION_TEST_SITE/lib/events-broker.jar

  echo "Copy $BROKER_TYPE events plugin"
  cp -f $DEPLOYMENT_LOCATION/events-kafka.jar $LOCATION_TEST_SITE/plugins/events-kafka.jar

  echo "Re-indexing"
  java -jar $DEPLOYMENT_LOCATION/gerrit.war reindex -d $LOCATION_TEST_SITE
fi

DOCKER_HOST_ENV=$(docker_host_env)
echo "Docker host environment: $DOCKER_HOST_ENV"
if [ "$DOCKER_HOST_ENV" = "mac" ];then
  export GERRIT_SITE_HOST="host.docker.internal"
  export NETWORK_MODE="bridge"
else
  export GERRIT_SITE_HOST="localhost"
  export NETWORK_MODE="host"
fi

export BROKER_PORT=9092
ensure_docker_compose_is_up_and_running "$BROKER_TYPE" "${BROKER_TYPE}_test_node" "docker-compose-$BROKER_TYPE.yaml"

echo "Re-deploying configuration files"
deploy_config_files $GERRIT_HOSTNAME $GERRIT_HTTPD_PORT $GERRIT_SHD_PORT
echo "Starting gerrit site"
$LOCATION_TEST_SITE/bin/gerrit.sh restart


echo "==============================="
echo "Current gerrit events-kafka setup"
echo "==============================="
echo "The admin password is 'secret'"
echo "deployment-location=$DEPLOYMENT_LOCATION"
echo "enable-https=$HTTPS_ENABLED"
echo
echo "GERRIT: http://$GERRIT_HOSTNAME:$GERRIT_HTTPD_PORT"
echo "Site: $LOCATION_TEST_SITE"
echo
echo "$HTTPS_CLONE_MSG"
echo

exit $?
