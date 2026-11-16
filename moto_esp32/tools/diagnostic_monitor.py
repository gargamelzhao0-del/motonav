"""Passively collect DIAG1 phone summaries and device logs; never toggles RTS/DTR or resets firmware."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import time
import serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', default='COM21')
    parser.add_argument('--seconds', type=int, default=3600)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 14400:
        parser.error('seconds must be in 1..14400')
    args.output.mkdir(parents=True, exist_ok=False)
    stats = {'phone_samples': 0, 'phone_live_wire_waiting': 0, 'invalid_json': 0}
    modes = {0: 'NONE', 1: 'WAITING', 2: 'LIVE', 3: 'ARRIVED', 4: 'STOPPED'}
    port = serial.Serial(port=None, baudrate=115200, timeout=0.25)
    port.dtr = False
    port.rts = False
    port.port = args.port
    port.open()
    print(f'Listening on {args.port} without reset; output={args.output}', flush=True)
    deadline = time.monotonic() + args.seconds
    pending = bytearray()
    try:
        with port, (args.output/'serial.log').open('ab') as raw, (args.output/'phone.jsonl').open('a', encoding='utf-8') as phone:
            while time.monotonic() < deadline:
                data = port.read(min(4096, max(1, port.in_waiting)))
                if not data:
                    continue
                raw.write(data)
                raw.flush()
                pending.extend(data)
                while b'\n' in pending:
                    line, _, rest = pending.partition(b'\n')
                    pending = bytearray(rest)
                    if b'phone_diag:' not in line:
                        continue
                    try:
                        start = line.index(b'{')
                        end = line.rindex(b'}') + 1
                        record = json.loads(line[start:end])
                        if not isinstance(record, dict) or record.get('schema') != 1:
                            raise ValueError('schema')
                    except (ValueError, UnicodeError):
                        stats['invalid_json'] += 1
                        continue
                    stats['phone_samples'] += 1
                    mismatch = record.get('phone_mode_at_tx') == 2 and record.get('wire_mode') == 1
                    stats['phone_live_wire_waiting'] += int(mismatch)
                    phone.write(json.dumps({'received_at': datetime.now(timezone.utc).isoformat(), 'record': record}, separators=(',', ':'))+'\n')
                    phone.flush()
                    stats['latest'] = record
                    temporary = args.output/'status.tmp'
                    temporary.write_text(json.dumps(stats), encoding='utf-8')
                    temporary.replace(args.output/'status.json')
                    print(f"DIAG seq={record.get('seq')} phone={modes.get(record.get('phone_mode_at_tx'))} wire={modes.get(record.get('wire_mode'))} reason={record.get('wire_reason')} fix_age_ms={record.get('frame_fix_age_ms')} instruction_age_ms={record.get('frame_instruction_age_ms')} nav_seq={record.get('nav_seq')} ack_seq={record.get('ack_seq')}", flush=True)
                if len(pending) > 8192:
                    pending.clear()
    except KeyboardInterrupt:
        pass
    finally:
        port.close()
        (args.output/'status.json').write_text(json.dumps(stats), encoding='utf-8')
        print(json.dumps(stats), flush=True)


if __name__ == '__main__':
    main()
