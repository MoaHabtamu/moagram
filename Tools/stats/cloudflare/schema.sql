CREATE TABLE IF NOT EXISTS installs (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    version_name TEXT NOT NULL,
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    update_count INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS arrivals (
    seen INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS arrivals_seen ON arrivals (seen);

CREATE TABLE IF NOT EXISTS daily (
    day TEXT NOT NULL,
    id TEXT NOT NULL,
    PRIMARY KEY (day, id)
);

CREATE TABLE IF NOT EXISTS login_limits (
    bucket TEXT PRIMARY KEY,
    failures INTEGER NOT NULL,
    seen INTEGER NOT NULL
);
