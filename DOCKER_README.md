# Usage with Docker

This document explains how to build and use the Docker image for this application.

## Prerequisites

- Docker must be installed on your machine.
- Leiningen must be installed locally to use the `lein docker` commands.

## Building the Docker Image

The project is configured with the `lein-docker` plugin. To build the image, run:

```bash
lein docker build
```
This command uses the image name `autho-pdp` configured in `project.clj`.

## Pushing the Image

If you have the necessary permissions for a Docker registry (e.g., Docker Hub), you can push the image with:

```bash
lein docker push
```

## Running the Docker Container

To run the container, you can use the standard `docker run` command:

```bash
docker run --rm -p 8080:8080 autho-pdp
```
The service will be available at `http://localhost:8080`.

### Running with Custom Configuration

To use local configuration files, you can mount them into the container:

```bash
docker run --rm -p 8080:8080 \
  -v "$(pwd)/resources/rules.edn:/usr/src/app/resources/rules.edn" \
  -v "$(pwd)/resources/pdp-prop.properties:/usr/src/app/resources/pdp-prop.properties" \
  autho-pdp
```
