#!/usr/bin/env bash
set -euo pipefail

server_root=/opt/geoworld-store/worlds/showcase/caveweaver-cw11-success-seed104729-v1
cd "$server_root"

exec nice -n 10 ionice -c 3 /opt/jdk21/bin/java \
  -Xms2G -Xmx6G \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -jar fabric-server-launch.jar nogui
