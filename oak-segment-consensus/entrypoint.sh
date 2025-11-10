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

# Run Java as user 999
exec gosu 999:999 java -jar /app/oak-segment-consensus.jar "$@"

