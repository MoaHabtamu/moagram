"""Remember anonymous Moagram installs without storing accounts or message contents."""
import re
import sqlite3
import threading
from datetime import datetime, timezone

UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
EVENTS = {'install', 'update', 'active'}
DAY = 24 * 60 * 60 * 1000
HOUR = 60 * 60 * 1000
NEW_INSTALL_LIMIT = 120


def utc_day(timestamp):
    return datetime.fromtimestamp(timestamp / 1000, timezone.utc).date().isoformat()


class UsageError(ValueError):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


class Store:
    def __init__(self, path):
        self._lock = threading.Lock()
        self._new_ids = []
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.execute('''CREATE TABLE IF NOT EXISTS installs (
            id TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            version_code INTEGER NOT NULL,
            version_name TEXT NOT NULL,
            first_seen INTEGER NOT NULL,
            last_seen INTEGER NOT NULL,
            update_count INTEGER NOT NULL
        )''')
        self._db.execute('''CREATE TABLE IF NOT EXISTS daily (
            day TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (day, id)
        )''')

    def close(self):
        self._db.close()

    def record(self, payload, now):
        if not isinstance(payload, dict):
            raise UsageError(400, 'Expected a JSON object')
        install_id = payload.get('install_id')
        event = payload.get('event')
        version = payload.get('version_code')
        name = payload.get('version_name', '')
        if not isinstance(install_id, str) or not UUID.fullmatch(install_id.lower()):
            raise UsageError(400, 'install_id must be a UUID')
        install_id = install_id.lower()
        if event not in EVENTS:
            raise UsageError(400, 'event must be install, update, or active')
        if isinstance(version, bool) or not isinstance(version, int) or not 0 < version <= 2100000000:
            raise UsageError(400, 'version_code is invalid')
        if not isinstance(name, str) or len(name) > 80 or any(ord(ch) < 32 for ch in name):
            raise UsageError(400, 'version_name is invalid')
        with self._lock:
            self._new_ids = [stamp for stamp in self._new_ids if now - stamp < HOUR]
            row = self._db.execute('SELECT kind FROM installs WHERE id = ?', (install_id,)).fetchone()
            if row is None:
                if len(self._new_ids) >= NEW_INSTALL_LIMIT:
                    raise UsageError(429, 'Too many new installs this hour')
                self._new_ids.append(now)
                kind = 'install' if event == 'install' else 'existing'
                self._db.execute(
                    'INSERT INTO installs VALUES (?, ?, ?, ?, ?, ?, ?)',
                    (install_id, kind, version, name, now, now, 1 if event == 'update' else 0))
            else:
                updates = 1 if event == 'update' else 0
                self._db.execute('''UPDATE installs
                    SET version_code = ?, version_name = ?, last_seen = ?,
                        update_count = update_count + ?
                    WHERE id = ?''', (version, name, now, updates, install_id))
            self._db.execute('INSERT OR IGNORE INTO daily (day, id) VALUES (?, ?)',
                             (utc_day(now), install_id))
            self._db.execute('DELETE FROM daily WHERE day < ?', (utc_day(now - 180 * DAY),))
            self._db.commit()

    def summary(self, now):
        with self._lock:
            def count(sql, *args):
                return self._db.execute(sql, args).fetchone()[0]
            versions = [{
                'version_code': version,
                'version_name': name,
                'new_installs': installs,
                'active_30d': active,
            } for version, name, installs, active in self._db.execute(
                '''SELECT version_code, MAX(version_name),
                          SUM(kind = 'install'),
                          SUM(last_seen >= ?)
                   FROM installs GROUP BY version_code ORDER BY version_code DESC''',
                (now - 30 * DAY,))]
            today = now // DAY * DAY
            start, end = today - 43 * DAY, today - 13 * DAY
            eligible, returned = self._db.execute('''SELECT COUNT(*),
                COALESCE(SUM(EXISTS(SELECT 1 FROM daily d WHERE d.id = i.id
                    AND d.day >= date(i.first_seen / 1000, 'unixepoch', '+7 days')
                    AND d.day < date(i.first_seen / 1000, 'unixepoch', '+14 days'))), 0)
                FROM installs i WHERE i.first_seen >= ? AND i.first_seen < ?
                AND EXISTS(SELECT 1 FROM daily d WHERE d.id = i.id
                    AND d.day = date(i.first_seen / 1000, 'unixepoch'))''', (start, end)).fetchone()
            return {
                'new_installs': count("SELECT COUNT(*) FROM installs WHERE kind = 'install'"),
                'existing_installs': count("SELECT COUNT(*) FROM installs WHERE kind = 'existing'"),
                'update_events': count('SELECT COALESCE(SUM(update_count), 0) FROM installs'),
                'active_1d': count('SELECT COUNT(*) FROM installs WHERE last_seen >= ?', now - DAY),
                'active_7d': count('SELECT COUNT(*) FROM installs WHERE last_seen >= ?', now - 7 * DAY),
                'active_30d': count('SELECT COUNT(*) FROM installs WHERE last_seen >= ?', now - 30 * DAY),
                'by_version': versions,
                'week_two': {
                    'eligible': eligible, 'returned': returned,
                    'cohort_start': utc_day(start), 'cohort_end': utc_day(end - DAY),
                },
            }
