#!/usr/bin/env bash
# Publish the Moagram web push relay to the Cloudflare account logged in on this computer.
set -euo pipefail
cd "$(dirname "$0")"
WRANGLER=(npx --yes wrangler@4)

node_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
if (( node_major < 22 )); then
    echo "Wrangler 4 needs Node.js 22 or newer (found $(node --version 2>/dev/null || echo none))."
    echo "With nvm: nvm use 24, then run this script again."
    exit 1
fi

if ! "${WRANGLER[@]}" whoami >/dev/null 2>&1; then
    echo "Log in first, in this terminal: npx wrangler login"
    exit 1
fi

"${WRANGLER[@]}" deploy
echo
echo "Copy the https://....workers.dev address from above and add it to local.properties:"
echo "MOAGRAM_PUSH_GATEWAY=https://THAT-ADDRESS.workers.dev"
echo "Then rebuild Moagram."
