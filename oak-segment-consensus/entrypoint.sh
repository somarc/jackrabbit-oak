#!/bin/sh
set -e

# Create and fix permissions on volume mount
mkdir -p /var/oak-chain/segmentstore-composite-mount-oak-chain
chown -R 999:999 /var/oak-chain

# Install gosu if not present
if ! command -v gosu >/dev/null 2>&1; then
    apt-get update
    apt-get install -y gosu
    rm -rf /var/lib/apt/lists/*
fi

# Use JAVA_OPTS if provided (for consensus configuration)
# JAVA_OPTS should contain -D system properties like:
# -Dconsensus.enabled=true -Dconsensus.mode=dag -Dconsensus.self.url=...
JAVA_ARGS=""
if [ -n "$JAVA_OPTS" ]; then
    JAVA_ARGS="$JAVA_OPTS"
fi

# Run Java as user 999
# Format: java [JAVA_OPTS] -jar /app/oak-segment-consensus.jar [CMD args]
exec gosu 999:999 java $JAVA_ARGS -jar /app/oak-segment-consensus.jar "$@"

