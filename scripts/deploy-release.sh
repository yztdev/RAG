#!/usr/bin/env bash
# Defaults to a local-only plan. See scripts/DEPLOY.md before a real release.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/deploy_release.py" "${@:-all}"
