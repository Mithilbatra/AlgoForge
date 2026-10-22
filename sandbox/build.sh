#!/bin/sh
# Builds the three sandbox images the worker uses (names must match algoforge.sandbox.images.* in application.yml).
set -e
cd "$(dirname "$0")"
docker build -q --build-arg BASE=python:3.12-slim -t algoforge-sandbox-python:1 .
docker build -q --build-arg BASE=gcc:14 -t algoforge-sandbox-cpp:1 .
docker build -q --build-arg BASE=eclipse-temurin:21-jdk -t algoforge-sandbox-java:1 .
