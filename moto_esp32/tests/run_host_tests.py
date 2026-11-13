"""Run firmware tests with a host C++ compiler; no device or Android SDK is needed."""
import argparse
from pathlib import Path
import os
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cxx', default=os.environ.get('CXX', 'g++'))
parser.add_argument('--android-source', type=Path, help='Optionally regenerate frames from this android-recovery checkout')
args = parser.parse_args()
subprocess.run([sys.executable, str(root / 'tests/check_architecture.py')], check=True)
with tempfile.TemporaryDirectory(prefix='motonav-firmware-tests-') as directory:
    output = Path(directory)
    fixture = root / 'tests/fixtures/nav3.frames'
    route_fixture = root / 'tests/fixtures/nav4.frames'
    if args.android_source:
        java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        source = args.android_source.resolve()
        classes = ['Simulation', 'Protocol', 'NavigationFrame', 'NavigationProtocol', 'DeviceRoute', 'RouteProtocol']
        sources = [source / 'app/src/main/java/com/motonav/companion' / (name + '.java') for name in classes]
        sources.append(source / 'tests/NavigationProtocolTests.java')
        sources.append(source / 'tests/RouteProtocolTests.java')
        subprocess.run([str(java), '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8',
                        '-d', str(output), *map(str, sources)], check=True)
        fixture = output / 'nav3.frames'
        subprocess.run([str(java), '-cp', str(output), 'NavigationProtocolTests', str(fixture)], check=True)
        assert fixture.read_bytes() == (root / 'tests/fixtures/nav3.frames').read_bytes(), 'Android protocol fixture changed'
        route_fixture = output / 'nav4.frames'
        subprocess.run([str(java), '-cp', str(output), 'RouteProtocolTests', str(route_fixture)], check=True)
        assert route_fixture.read_bytes() == (root / 'tests/fixtures/nav4.frames').read_bytes(), 'Android NAV4 fixture changed'
    for name in ['navigation_protocol_test', 'route_protocol_test', 'display_model_test', 'map_projection_test', 'map_raster_test', 'navigation_session_test', 'display_qspi_test']:
        executable = output / (name + ('.exe' if os.name == 'nt' else ''))
        subprocess.run([args.cxx, '-std=c++17', '-Wall', '-Wextra', '-Werror',
                        *[f'-I{path}' for path in [root / 'components/motonav_core/include',
                            root / 'components/motonav_graphics/include', root / 'components/motonav_display',
                            root / 'components/motonav_graphics']],
                        *([f'-I{root / "tests/host/qspi_sdk"}'] if name == 'display_qspi_test' else []),
                        str(root / 'tests/host' / (name + '.cpp')),
                        *([str(root / 'components/motonav_display/qspi_panel.cpp')] if name == 'display_qspi_test' else []),
                        '-o', str(executable)], check=True)
        subprocess.run([str(executable), *([str(fixture)] if name == 'navigation_protocol_test' else [str(route_fixture)] if name == 'route_protocol_test' else [])], check=True)
