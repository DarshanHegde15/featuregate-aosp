#!/usr/bin/env bash
# Maintainer script: regenerates patches/, src/ and app/ from the AOSP tree.
# Run after every committed step, then commit and push this repo.
set -euo pipefail

AOSP="${AOSP:-/data/android/aosp/aosp15}"
AUTHOR="${AUTHOR:-Darshan}"          # must match your git user.name in the AOSP repos
REPO="$(cd "$(dirname "$0")/.." && pwd)"

export_patches() {   # $1 = AOSP project path, $2 = folder name under patches/
    local proj="$1" name="$2" first
    cd "$AOSP/$proj"
    first="$(git log --reverse --format=%H --author="$AUTHOR" | head -1)"
    if [[ -z "$first" ]]; then
        echo "no commits by $AUTHOR in $proj, skipping"; return
    fi
    rm -rf "$REPO/patches/$name"
    mkdir -p "$REPO/patches/$name"
    git format-patch -q "$first^..HEAD" -o "$REPO/patches/$name"
    echo "$proj: $(ls "$REPO/patches/$name" | wc -l) patches"
}

export_patches frameworks/base frameworks_base
export_patches system/sepolicy system_sepolicy

# Snapshot of the new files, at their AOSP paths
rm -rf "$REPO/src"
FB="$AOSP/frameworks/base"
mkdir -p "$REPO/src/frameworks/base/core/java/android/app" \
         "$REPO/src/frameworks/base/services/core/java/com/android/server"
cp -r "$FB/core/java/android/app/featuregate" "$REPO/src/frameworks/base/core/java/android/app/"
cp -r "$FB/services/core/java/com/android/server/featuregate" \
      "$REPO/src/frameworks/base/services/core/java/com/android/server/"

# Test app (it has its own git repo in AOSP; copy the sources without .git)
mkdir -p "$REPO/app"
rsync -a --delete --exclude .git "$AOSP/packages/apps/FeatureGateConsole/" "$REPO/app/FeatureGateConsole/"

echo "Done. Review with: cd $REPO && git status"
