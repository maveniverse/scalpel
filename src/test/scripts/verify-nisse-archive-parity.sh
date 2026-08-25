#!/usr/bin/env bash
#
# verify-nisse-archive-parity.sh
#
# Verifies that nisse.jgit.dynamicVersion resolves to a valid Maven version
# in both a git checkout and a source archive (no .git directory).
#
# Usage:  ./src/test/scripts/verify-nisse-archive-parity.sh
#
# Requires: git, mvnw wrapper (resolved from the repo root)
#
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# Resolve project.version from Maven via help:evaluate
resolve_version() {
    local dir="$1"
    (cd "$dir" && ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout -B 2>/dev/null)
}

printf '=== Test 1: git checkout build ===\n'
CHECKOUT_VERSION="$(resolve_version "$REPO_ROOT")"
printf 'Checkout version: %s\n' "$CHECKOUT_VERSION"

if [ -z "$CHECKOUT_VERSION" ]; then
    printf 'FAIL: checkout version is empty\n' >&2; exit 1
fi
if printf '%s' "$CHECKOUT_VERSION" | grep -qF '$Format'; then
    printf 'FAIL: checkout version contains unexpanded placeholder\n' >&2; exit 1
fi

printf '\n=== Test 2: source archive build (no .git) ===\n'
git -C "$REPO_ROOT" archive --prefix=archive-test/ HEAD | tar -xf - -C "$WORK_DIR"
ARCHIVE_DIR="$WORK_DIR/archive-test"

# Verify export-subst expanded the placeholders
ARCHIVE_PROPS="$ARCHIVE_DIR/.mvn/nisse.properties"
if grep -qF '$Format:' "$ARCHIVE_PROPS" 2>/dev/null; then
    printf 'FAIL: nisse.properties still contains unexpanded $Format:..$ placeholder\n' >&2
    printf 'Content:\n' >&2
    cat "$ARCHIVE_PROPS" >&2
    exit 1
fi
printf 'Archive nisse.properties (expanded):\n'
grep -v '^#' "$ARCHIVE_PROPS" | grep -v '^$'

ARCHIVE_VERSION="$(resolve_version "$ARCHIVE_DIR")"
printf 'Archive version: %s\n' "$ARCHIVE_VERSION"

if [ -z "$ARCHIVE_VERSION" ]; then
    printf 'FAIL: archive version is empty\n' >&2; exit 1
fi
if printf '%s' "$ARCHIVE_VERSION" | grep -qF '$Format'; then
    printf 'FAIL: archive version contains unexpanded placeholder\n' >&2; exit 1
fi

printf '\n=== Test 3: tagged-commit parity ===\n'
# On a tagged commit both versions should be identical
LATEST_TAG="$(git -C "$REPO_ROOT" describe --tags --exact-match HEAD 2>/dev/null || true)"
if [ -n "$LATEST_TAG" ]; then
    if [ "$CHECKOUT_VERSION" = "$ARCHIVE_VERSION" ]; then
        printf 'PASS: tagged commit — versions match: %s\n' "$CHECKOUT_VERSION"
    else
        printf 'FAIL: tagged commit — versions differ: checkout=%s archive=%s\n' \
            "$CHECKOUT_VERSION" "$ARCHIVE_VERSION" >&2
        exit 1
    fi
else
    printf 'SKIP: HEAD is not a tagged commit (checkout=%s, archive=%s)\n' \
        "$CHECKOUT_VERSION" "$ARCHIVE_VERSION"
    printf '  (expected: formats differ between JGit dynamic version and git-describe)\n'
fi

printf '\n=== Test 4: no-reachable-tag guard ===\n'
# Verify that %(describe:tags=true) expands to empty when no tag is reachable,
# and document this as a known limitation.
ORPHAN_DIR="$WORK_DIR/orphan-repo"
mkdir -p "$ORPHAN_DIR"
git -C "$ORPHAN_DIR" init -q
git -C "$ORPHAN_DIR" commit --allow-empty -m "orphan" -q
ORPHAN_DESCRIBE="$(git -C "$ORPHAN_DIR" log -1 --format='%(describe:tags=true)')"
if [ -z "$ORPHAN_DESCRIBE" ]; then
    printf 'PASS: confirmed %%(describe:tags=true) is empty when no tag is reachable\n'
    printf '  (known limitation: source archives must be created from tagged commits)\n'
else
    printf 'INFO: %%(describe:tags=true) returned "%s" with no tags\n' "$ORPHAN_DESCRIBE"
fi

printf '\n=== All tests passed ===\n'
printf 'checkout=%s  archive=%s\n' "$CHECKOUT_VERSION" "$ARCHIVE_VERSION"
