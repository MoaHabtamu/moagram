#!/usr/bin/env bash
# Publish the Moagram usage collector to the Cloudflare account logged in on this computer.
set -euo pipefail
cd "$(dirname "$0")"
WRANGLER=(npx --yes wrangler@4)

if ! "${WRANGLER[@]}" whoami >/dev/null 2>&1; then
    echo "Log in first, in this terminal: npx wrangler login"
    exit 1
fi

if grep -q REPLACE_WITH_DATABASE_ID wrangler.toml; then
    create="$("${WRANGLER[@]}" d1 create moagram-usage)"
    printf '%s\n' "$create"
    id="$(printf '%s\n' "$create" | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1)"
    if [[ -z "$id" ]]; then
        echo "Could not read the new database id. Paste it into wrangler.toml as database_id."
        exit 1
    fi
    sed -i "s/REPLACE_WITH_DATABASE_ID/${id}/" wrangler.toml
fi

"${WRANGLER[@]}" d1 execute moagram-usage --remote --file=schema.sql
"${WRANGLER[@]}" deploy
echo
echo "Copy the https://....workers.dev address from above."
echo "Add this to updates/android.json, using that address, then push that file to master:"
echo "\"usage_url\": \"https://THAT-ADDRESS.workers.dev/v1/event\""
