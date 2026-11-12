"""Capture a bounded log from an explicitly selected serial port."""
import argparse
from pathlib import Path
import time

import serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True)
    parser.add_argument("--seconds", type=float, default=100)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reset-usb", action="store_true",
                        help="Reset the selected ESP32 USB Serial/JTAG device before capture")
    args = parser.parse_args()
    if not 0 < args.seconds <= 3600:
        parser.error("--seconds must be between 0 and 3600")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Do not reset a board just by opening its port.
    port = serial.Serial(port=None, baudrate=115200, timeout=0.2)
    port.dtr = False
    port.rts = False
    port.port = args.port
    with args.output.open("xb") as log, port:
        if args.reset_usb:
            from esptool.reset import HardReset
            HardReset(port, uses_usb=True)()
        deadline = time.monotonic() + args.seconds
        pending = b""
        while time.monotonic() < deadline:
            chunk = port.read(max(1, min(port.in_waiting, 4096)))
            if not chunk:
                continue
            log.write(chunk)
            log.flush()
            pending += chunk
            while b"\n" in pending:
                line, pending = pending.split(b"\n", 1)
                print(line.decode("utf-8", errors="replace").rstrip(), flush=True)
            if len(pending) > 4096:
                print(pending.decode("utf-8", errors="replace"), flush=True)
                pending = b""
        if pending:
            print(pending.decode("utf-8", errors="replace"), flush=True)
    print(f"Saved {args.output.stat().st_size} bytes to {args.output}")


if __name__ == "__main__":
    main()
