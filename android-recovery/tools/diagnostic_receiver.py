"""Temporary authenticated MotoNav diagnostic collector. Bind only to loopback; publish with an HTTPS tunnel."""
import argparse
from collections import OrderedDict, deque
from datetime import datetime, timezone
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import re
import secrets
import threading
import time

EVENTS = set('session_start session_stop user_mark environment lifecycle location_start location_stop location_callback location_decision location_state location_retry location_exception route_action route_result search_result navigation_state ble_state java_crash'.split())
TEXT = set('owner reason state error_class manufacturer model version phase action'.split())
NUMBERS = set('api version_code error source accuracy_m measurement_age_ms elapsed_age_ms wall_age_ms callback_gap_ms observation_age_ms navigation_age_ms generation satellites gps_status trusted_level count route_age_ms moved_m icon fix_age_ms dropped queue line'.split())
FLAGS = set('fine coarse system_location foreground consent map_ready requested accepted eligible mock network wifi cellular vpn battery_saver idle background_exempt navigating gps_weak destination selected route_valid'.split())
REQUIRED = {'schema', 'session', 'seq', 'at_ms', 'event'}
MAX_BODY = 32768
MAX_STORAGE = 20 * 1024 * 1024


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('duplicate_field')
        value[key] = item
    return value


def validate_batch(data):
    lines = data.decode('utf-8').splitlines()
    if not 1 <= len(lines) <= 24:
        raise ValueError('batch_size')
    records = []
    for line in lines:
        if len(line) > 4096:
            raise ValueError('line_size')
        record = json.loads(line, object_pairs_hook=unique_object)
        if not isinstance(record, dict) or not REQUIRED <= set(record) or set(record) - REQUIRED - TEXT - NUMBERS - FLAGS:
            raise ValueError('fields')
        if type(record['schema']) is not int or record['schema'] != 1 or record['event'] not in EVENTS:
            raise ValueError('schema')
        if not isinstance(record['session'], str) or not re.fullmatch('[0-9a-f]{32}', record['session']):
            raise ValueError('session')
        for key in ('seq', 'at_ms'):
            if type(record[key]) is not int or not 0 <= record[key] <= 10**12:
                raise ValueError('sequence_or_clock')
        if not record['seq']:
            raise ValueError('sequence')
        for key in set(record) - REQUIRED:
            value = record[key]
            if key in TEXT:
                if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.$ ?-]{0,80}', value):
                    raise ValueError('text')
            elif key in FLAGS:
                if type(value) is not bool:
                    raise ValueError('flag')
            elif type(value) not in (int, float) or not math.isfinite(value) or abs(value) > 1e15:
                raise ValueError('number')
        if records and (record['session'] != records[0]['session'] or record['seq'] <= records[-1]['seq']):
            raise ValueError('batch_order')
        records.append(record)
    return records


class ReceiverState:
    def __init__(self, directory, hours=6):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        config = self.directory/'session.json'
        if config.exists():
            self.config = json.loads(config.read_text())
        else:
            self.config = {'token': secrets.token_urlsafe(32), 'expires_at': time.time() + hours * 3600}
            with config.open('x', encoding='utf-8') as output:
                json.dump(self.config, output)
            config.chmod(0o600)
        self.events = self.directory/'events.jsonl'
        self.lock = threading.Lock()
        self.sessions = {}
        self.requests = deque()
        self.total = 0
        if self.events.exists():
            if self.events.stat().st_size > MAX_STORAGE:
                raise ValueError('storage_limit')
            for line in self.events.read_text(encoding='utf-8').splitlines():
                record = json.loads(line)['record']
                self.remember(record)
                self.total += 1

    def remember(self, record):
        state = self.sessions.setdefault(record['session'], {'last': 0, 'hashes': OrderedDict()})
        state['last'] = record['seq']
        state['hashes'][record['seq']] = self.digest(record)
        while len(state['hashes']) > 1024:
            state['hashes'].popitem(last=False)

    @staticmethod
    def digest(record):
        return hashlib.sha256(json.dumps(record, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

    def accept(self, records):
        with self.lock:
            now = time.time()
            while self.requests and now - self.requests[0] > 5:
                self.requests.popleft()
            if len(self.requests) >= 30:
                return 429, {'error': 'rate_limit'}
            self.requests.append(now)
            session = records[0]['session']
            if session not in self.sessions and len(self.sessions) >= 32:
                return 429, {'error': 'session_limit'}
            state = self.sessions.get(session, {'last': 0, 'hashes': {}})
            for record in records:
                if record['seq'] <= state['last'] and state['hashes'].get(record['seq']) != self.digest(record):
                    return 409, {'error': 'conflicting_replay'}
            new = [record for record in records if record['seq'] > state['last']]
            received = datetime.now(timezone.utc).isoformat()
            encoded = ''.join(json.dumps({'received_at': received, 'record': record}, separators=(',', ':'))+'\n' for record in new).encode()
            if (self.events.stat().st_size if self.events.exists() else 0) + len(encoded) > MAX_STORAGE:
                return 413, {'error': 'storage_limit'}
            with self.events.open('ab') as output:
                output.write(encoded)
                output.flush()
            for record in new:
                self.remember(record)
            self.total += len(new)
            status = {'total_events': self.total, 'sessions': len(self.sessions), 'last_received_at': received,
                      'last_event': records[-1]['event'], 'last_seq': records[-1]['seq'],
                      'expires_at': self.config['expires_at']}
            temporary = self.directory/'status.tmp'
            temporary.write_text(json.dumps(status), encoding='utf-8')
            temporary.replace(self.directory/'status.json')
            return 200, {'accepted': len(new), 'last_seq': records[-1]['seq']}


class Collector(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, state):
        if address[0] != '127.0.0.1':
            raise ValueError('loopback_required')
        self.state = state
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, *args):
        pass  # No request paths, IP addresses, credentials or request bodies in access logs.

    def reply(self, status, value):
        body = json.dumps(value, separators=(',', ':')).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        if self.path in ('/', '/health'):
            self.reply(200, {'service': 'MotoNav diagnostics', 'status': 'ready' if time.time() < self.server.state.config['expires_at'] else 'expired'})
        else:
            self.reply(404, {'error': 'not_found'})

    def do_POST(self):
        state = self.server.state
        if self.path != '/v1/events':
            return self.reply(404, {'error': 'not_found'})
        authorization = self.headers.get('Authorization', '')
        if not hmac.compare_digest(authorization.encode(), ('Bearer '+state.config['token']).encode()):
            return self.reply(401, {'error': 'unauthorized'})
        if time.time() >= state.config['expires_at']:
            return self.reply(410, {'error': 'expired'})
        if self.headers.get('Transfer-Encoding') or self.headers.get('Content-Type') != 'application/x-ndjson':
            return self.reply(400, {'error': 'content_type'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= MAX_BODY:
                return self.reply(413, {'error': 'size'})
            body = self.rfile.read(length)
            if len(body) != length:
                raise ValueError('truncated')
            records = validate_batch(body)
        except (ValueError, TypeError, UnicodeError, TimeoutError):
            return self.reply(400, {'error': 'invalid_record'})
        try:
            code, reply = state.accept(records)
            self.reply(code, reply)
        except OSError:
            self.reply(503, {'error': 'storage_unavailable'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--hours', type=float, default=6)
    args = parser.parse_args()
    if not 0 < args.hours <= 24:
        parser.error('hours must be in (0, 24]')
    state = ReceiverState(args.directory, args.hours)
    server = Collector(('127.0.0.1', args.port), state)
    (args.directory/'listener.json').write_text(json.dumps({'port': server.server_port}), encoding='utf-8')
    print(json.dumps({'listening': '127.0.0.1', 'port': server.server_port, 'expires_at': state.config['expires_at']}), flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
