#!/usr/bin/env python3
"""Collect anonymous Moagram install and activity counts.

Listens on 127.0.0.1:8787. Put it behind HTTPS and set updates/android.json
usage_url to https://YOUR-HOST/v1/event. Phones that have the counting build
then report a fresh install, an update, or a daily open.
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from usage_store import Store, UsageError

ROOT = Path(__file__).resolve().parents[2]
STORE = None


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_GET(self):
        if self.path.split('?', 1)[0] == '/v1/summary':
            self._send(200, json.dumps(STORE.summary(self._now()), indent=2).encode())
            return
        if self.path.split('?', 1)[0] == '/':
            summary = STORE.summary(self._now())
            heard = summary['new_installs'] + summary['existing_installs']
            text = (
                f"Opened Moagram in the last day: {summary['active_1d']}\n"
                f"Opened Moagram in the last 7 days: {summary['active_7d']}\n"
                f"Opened Moagram in the last 30 days: {summary['active_30d']}\n"
                f"Phones that have reported: {heard}\n"
            )
            self._send(200, text.encode(), 'text/plain; charset=utf-8')
            return
        self._send(404, b'Not found\n', 'text/plain; charset=utf-8')

    def do_POST(self):
        if self.path.split('?', 1)[0] != '/v1/event':
            self._send(404, b'Not found\n', 'text/plain; charset=utf-8')
            return
        length = int(self.headers.get('Content-Length', '0') or 0)
        if length <= 0 or length > 1024:
            self._send(400, b'Expected a small JSON body\n', 'text/plain; charset=utf-8')
            return
        try:
            payload = json.loads(self.rfile.read(length).decode())
            STORE.record(payload, self._now())
        except UsageError as error:
            self._send(error.status, (str(error) + '\n').encode(), 'text/plain; charset=utf-8')
            return
        except (UnicodeError, json.JSONDecodeError, ValueError):
            self._send(400, b'Invalid JSON\n', 'text/plain; charset=utf-8')
            return
        self._send(204, b'')

    def log_message(self, fmt, *args):
        return

    def _now(self):
        return int(time.time() * 1000)

    def _send(self, status, body, content_type='application/json; charset=utf-8'):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        if body:
            self.wfile.write(body)


def main():
    global STORE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8787)
    parser.add_argument('--db', type=Path, default=Path(__file__).resolve().parent / 'usage.sqlite')
    args = parser.parse_args()
    STORE = Store(args.db)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f'Recording usage in {args.db}')
    print(f'Listening on http://{args.host}:{args.port}/')
    server.serve_forever()


if __name__ == '__main__':
    main()
