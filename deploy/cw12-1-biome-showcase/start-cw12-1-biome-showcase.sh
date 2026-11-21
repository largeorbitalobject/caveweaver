#!/usr/bin/env bash
set -euo pipefail

server_root=/opt/geoworld-store/worlds/showcase/caveweaver-cw12-1-biome-aware-seed6942694997652268-v1
cd "$server_root"

exec nice -n 10 ionice -c 3 /opt/jdk21/bin/java \
  -Xms1G -Xmx3G \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -jar fabric-server-launch.jar nogui
