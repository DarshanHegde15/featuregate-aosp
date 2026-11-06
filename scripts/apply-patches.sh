#!/usr/bin/env bash
# Reader script: applies FeatureGate to a clean android-15.0.0_r36 tree.
# Usage: ./scripts/apply-patches.sh /path/to/aosp
set -euo pipefail

AOSP="${1:?usage: $0 /path/to/aosp}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

(cd "$AOSP/frameworks/base" && git am "$REPO"/patches/frameworks_base/*.patch)
(cd "$AOSP/system/sepolicy" && git am "$REPO"/patches/system_sepolicy/*.patch)
cp -r "$REPO/app/FeatureGateConsole" "$AOSP/packages/apps/"

echo "Applied. Build the app with: m FeatureGateConsole"
