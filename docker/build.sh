#!/usr/bin/env bash

if [[ $# -lt 1 ]]; then
  echo "Usage: ${0} [version] [project_dir]"
  exit 1
fi

VERSION=$1

echo "Building image [helidon-pbj-capacity-test-2:${VERSION}]"
echo

# run docker build
#docker buildx build --platform=linux/amd64 --load -t "helidon-pbj-capacity-test-2:${VERSION}" --build-context distributions=../build/distributions --build-arg VERSION="${VERSION}" . || exit "${?}"
docker buildx build --load -t "helidon-pbj-capacity-test-2:${VERSION}" --build-context distributions=../build/distributions --build-arg VERSION="${VERSION}" . || exit "${?}"

echo
echo "Image [helidon-pbj-capacity-test-2:${VERSION}] built successfully!"
