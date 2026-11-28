#!/usr/bin/env sh
set -e
HERE="$(CDPATH= cd -P "$(dirname "$0")" && pwd)"
cd "$HERE"
exec java \
  -jar conductor.jar --local --name OnlyPC  --live --music-dir "/path/to/prefix/Documents/The Lord of the Rings Online/music"
