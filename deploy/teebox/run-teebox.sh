#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
JAR_FILE="$BASE_DIR/lib/propertee-teebox.jar"
CONF_FILE=${PROPERTEE_TEEBOX_CONFIG:-"$BASE_DIR/conf/teebox.properties"}
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}java

if [ ! -f "$JAR_FILE" ]; then
    echo "TeeBox server jar not found: $JAR_FILE" 1>&2
    exit 1
fi

if [ ! -f "$CONF_FILE" ]; then
    echo "TeeBox server config not found: $CONF_FILE" 1>&2
    exit 1
fi

exec "$JAVA_BIN" ${JAVA_OPTS:-} -jar "$JAR_FILE" --config "$CONF_FILE" "$@"
