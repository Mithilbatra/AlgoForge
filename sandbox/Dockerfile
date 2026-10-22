# One image per language: built with BASE=python:3.12-slim | gcc:14 | eclipse-temurin:21-jdk (see build.sh).
ARG BASE
FROM ${BASE}
RUN apt-get update && apt-get install -y --no-install-recommends time && rm -rf /var/lib/apt/lists/*
COPY af-run /usr/local/bin/af-run
USER 65534:65534
