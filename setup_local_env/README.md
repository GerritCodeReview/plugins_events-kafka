# Local environment setup

This script configures a full environment to simulate a Gerrit Multi-Site setup.
The environment is composed by:

- 1 gerrit instance deployed by default in /tmp
- 1 Kafka broker node 
- 1 Zookeeper

## Requirements

- java
- docker and docker-compose
- wget
- envsubst

## Examples

Simplest setup with all default values and cleanup previous deployment. Thiswill deploy kafka broker

```bash
sh setup_local_env/setup.sh --release-war-file /path/to/gerrit.war 
```

Cleanup the previous deployments

```bash
sh setup_local_env/setup.sh --just-cleanup-env true
```

Help

```bash
Usage: sh ./setup.sh [--option ]

[--release-war-file]            Location to release.war file
[--new-deployment]              Cleans up previous gerrit deployment and re-installs it. default true
[--deployment-location]         Base location for the test deployment; default /tmp

[--gerrit-canonical-host]       The default host for Gerrit to be accessed through; default localhost
[--gerrit-canonical-port]       The default port for Gerrit to be accessed throug; default 8080

[--gerrit-httpd-port]          Gerrit Instance  http port; default 8080
[--gerrit-sshd-port]           Gerrit Instance  sshd port; default 29418

[--replication-delay]           Replication delay across the two instances in seconds

[--just-cleanup-env]            Cleans up previous deployment; default false

[--enabled-https]               Enabled https; default true
```

## Limitations
- Assumes the ssh replication is done always on port 22 on both instances
- When cloning projects via ssh, public keys entries are added to `known_hosts`
  - Clean up the old entries when doing a new deployment, otherwise just use HTTP
