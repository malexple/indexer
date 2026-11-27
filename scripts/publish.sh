#!/usr/bin/env bash
# Build and publish the images mcp-sql, mcp-sql-bot and mcp-sql-web.
# Before the first run: docker login
# Usage: scripts/publish.sh [version] [--skip-tests] [--no-latest] [--no-push]
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="0.1.0"
REGISTRY="${REGISTRY:-malexple}"
SKIP_TESTS=0
NO_LATEST=0
NO_PUSH=0
for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    --no-latest) NO_LATEST=1 ;;
    --no-push) NO_PUSH=1 ;;
    *) VERSION="$arg" ;;
  esac
done

[ "$SKIP_TESTS" = 1 ] || ./gradlew test
./gradlew :mcp-sql-service:bootJar :mcp-sql-bot:bootJar :mcp-sql-web:bootJar

IMAGES=("mcp-sql:mcp-sql-service" "mcp-sql-bot:mcp-sql-bot" "mcp-sql-web:mcp-sql-web")

for entry in "${IMAGES[@]}"; do
  name="${entry%%:*}"; dir="${entry##*:}"
  tags=(-t "$REGISTRY/$name:$VERSION")
  [ "$NO_LATEST" = 1 ] || tags+=(-t "$REGISTRY/$name:latest")
  docker build --platform linux/amd64 "${tags[@]}" "$dir"
done

if [ "$NO_PUSH" = 1 ]; then
  echo "Images are built, push skipped (--no-push)."
  exit 0
fi

for entry in "${IMAGES[@]}"; do
  name="${entry%%:*}"
  docker push "$REGISTRY/$name:$VERSION"
  [ "$NO_LATEST" = 1 ] || docker push "$REGISTRY/$name:latest"
done

echo "Done, version $VERSION. On the server set MCPSQL_TAG=$VERSION in .env, then: docker compose pull && docker compose up -d"
