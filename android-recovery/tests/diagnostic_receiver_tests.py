"""Exercise the public receiver boundary with real Java-generated diagnostic records."""
import http.client
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import threading

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'tools'))
import diagnostic_receiver as receiver

fixtures = Path(sys.argv[1]).read_bytes().splitlines()
for fixture in fixtures:
    assert receiver.validate_batch(fixture)
with TemporaryDirectory(prefix='motonav-diag-test-') as folder:
    state = receiver.ReceiverState(folder)
    server = receiver.Collector(('127.0.0.1', 0), state)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    def request(body, token=None, path='/v1/events', method='POST'):
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        connection.request(method, path, body=body, headers={
            'Authorization': 'Bearer ' + (state.config['token'] if token is None else token),
            'Content-Type': 'application/x-ndjson'})
        response = connection.getresponse()
        status, value = response.status, json.loads(response.read())
        connection.close()
        return status, value
    try:
        assert request(fixtures[0], token='invalid')[0] == 401
        assert not state.events.exists()
        status, first = request(fixtures[0])
        assert status == 200 and first['accepted'] == 1
        assert request(fixtures[0])[1]['accepted'] == 0
        assert len(state.events.read_text().splitlines()) == 1
        altered = json.loads(fixtures[0]); altered['reason'] = 'changed'
        assert request(json.dumps(altered))[0] == 409
        for key in ('latitude', 'longitude', 'key', 'token', 'plate', 'message'):
            invalid = json.loads(fixtures[1]); invalid[key] = 'not_to_be_logged'
            assert request(json.dumps(invalid))[0] == 400
        invalid = json.loads(fixtures[1]); invalid['accuracy_m'] = float('inf')
        assert request(json.dumps(invalid))[0] == 400
        assert request(fixtures[0][:-1] + b',"seq":999}')[0] == 400
        assert request(b'x' * (receiver.MAX_BODY+1))[0] == 413
        assert request(b'', path='/events.jsonl', method='GET')[0] == 404
        for offset in range(1, len(fixtures), 24):
            status, result = request(b'\n'.join(fixtures[offset:offset+24]))
            assert status == 200 and result['accepted'] == len(fixtures[offset:offset+24])
        assert 'not_to_be_logged' not in state.events.read_text()
        assert state.config['token'] not in state.events.read_text()
        restored = receiver.ReceiverState(folder)
        assert restored.total == state.total == len(fixtures)
        assert restored.accept(receiver.validate_batch(fixtures[-1]))[1]['accepted'] == 0
        state.config['expires_at'] = 0
        assert request(fixtures[-1])[0] == 410
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=3)
print('PASS: Java-to-HTTP collector, auth, privacy schema, replay, restart, limits and expiry')
