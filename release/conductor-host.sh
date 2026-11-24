#!/usr/bin/env sh
set -e
HERE="$(CDPATH= cd -P "$(dirname "$0")" && pwd)"
cd "$HERE"
exec java \
  -jar conductor.jar --host --name MasterPC  --key 'setan8digitormorekey'  --port 38791  --live --music-dir "/path/to/prefix/Documents/The Lord of the Rings Online/music"
