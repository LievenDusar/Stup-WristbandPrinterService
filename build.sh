#!/usr/bin/env sh
# Build the shared Java 21 base image that the app image extends.
# Run this once, and again after changing docker/base/Dockerfile, BEFORE any
# `docker compose build` or `docker compose up --build`.
set -eu
docker build -t wristband-base:21 -f docker/base/Dockerfile .
echo "Built wristband-base:21"
