"""Read firmware revision and NUS properties on ONE explicitly selected device.

Requires bleak on the development computer. Does not request pairing, write
navigation data, erase bonds, or flash firmware. Disconnect the phone first.
"""
import argparse
import asyncio
import json
from pathlib import Path
from bleak import BleakClient, BleakScanner

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--address', required=True)
parser.add_argument('--expected-version', required=True)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
if args.output.exists():
    raise SystemExit(f'Output already exists: {args.output}')


async def main():
    device = await BleakScanner.find_device_by_address(args.address, timeout=15)
    if device is None:
        raise RuntimeError('The selected device is not advertising')
    async with BleakClient(device, pair=False, timeout=20, winrt={'use_cached_services': False}) as client:
        version = bytes(await client.read_gatt_char('00002a26-0000-1000-8000-00805f9b34fb')).decode('ascii')
        rx = client.services.get_characteristic('6e400002-b5a3-f393-e0a9-e50e24dcca9e')
        tx = client.services.get_characteristic('6e400003-b5a3-f393-e0a9-e50e24dcca9e')
        assert version == args.expected_version, version
        assert rx and 'write' in rx.properties
        assert tx and 'notify' in tx.properties
        result = {'device': device.name, 'address': args.address, 'firmware': version,
                  'rx': rx.properties, 'tx': tx.properties,
                  'pairing_requested': False, 'navigation_sent': False}
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(result, output, indent=2)
        output.write('\n')
    print(json.dumps(result))


asyncio.run(main())
