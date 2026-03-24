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

# Ensure dataDir exists
DATA_DIR=$(grep -E '^\s*propertee\.teebox\.dataDir\s*=' "$CONF_FILE" | sed 's/^[^=]*=\s*//' | tr -d '[:space:]')
if [ -n "$DATA_DIR" ] && [ ! -d "$DATA_DIR" ]; then
    mkdir -p "$DATA_DIR" || { echo "Failed to create dataDir: $DATA_DIR" 1>&2; exit 1; }
fi

LOG4J_CONF=${PROPERTEE_TEEBOX_LOG4J:-"$BASE_DIR/conf/log4j2.xml"}
LOG4J_OPTS=""
if [ -f "$LOG4J_CONF" ]; then
    LOG4J_OPTS="-Dlog4j.configurationFile=$LOG4J_CONF"
fi

exec "$JAVA_BIN" ${JAVA_OPTS:-} $LOG4J_OPTS -jar "$JAR_FILE" --config "$CONF_FILE" "$@"
