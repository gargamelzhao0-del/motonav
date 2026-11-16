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
    plan_fixture = root / 'tests/fixtures/nav4-plan.frames'
    diagnostic_fixture = root / 'tests/fixtures/diag1.frames'
    scene_fixture = root / 'tests/fixtures/nav4-scene.frames'
    details_fixture = root / 'tests/fixtures/nav4-details.frames'
    position_fixture = root / 'tests/fixtures/nav4-position.frames'
    if args.android_source:
        java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        source = args.android_source.resolve()
        classes = ['Simulation', 'Protocol', 'NavigationFrame', 'NavigationProtocol', 'DeviceRoute', 'Basemap', 'VectorTile', 'LocalMapBuilder', 'MapTileStore', 'RouteProtocol', 'NavigationDetails', 'BleDiagnosticProtocol']
        sources = [source / 'app/src/main/java/com/motonav/companion' / (name + '.java') for name in classes]
        sources.append(source / 'tests/NavigationProtocolTests.java')
        sources.append(source / 'tests/RouteProtocolTests.java')
        sources.append(source / 'tests/RoutePlanTests.java')
        sources.append(source / 'tests/BasemapTests.java')
        sources.append(source / 'tests/BleDiagnosticProtocolTests.java')
        sources.append(source / 'tests/NavigationDetailsTests.java')
        sources.append(source / 'tests/MapPositionTests.java')
        subprocess.run([str(java), '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8',
                        '-d', str(output), *map(str, sources)], check=True)
        position_fixture = output / 'nav4-position.frames'
        subprocess.run([str(java), '-cp', str(output), 'MapPositionTests', str(position_fixture)], check=True)
        assert position_fixture.read_bytes() == (root / 'tests/fixtures/nav4-position.frames').read_bytes(), 'Position fixture changed'
        details_fixture = output / 'nav4-details.frames'
        subprocess.run([str(java), '-cp', str(output), 'NavigationDetailsTests', str(details_fixture)], check=True)
        assert details_fixture.read_bytes() == (root / 'tests/fixtures/nav4-details.frames').read_bytes(), 'HUD fixture changed'
        fixture = output / 'nav3.frames'
        subprocess.run([str(java), '-cp', str(output), 'NavigationProtocolTests', str(fixture)], check=True)
        assert fixture.read_bytes() == (root / 'tests/fixtures/nav3.frames').read_bytes(), 'Android protocol fixture changed'
        route_fixture = output / 'nav4.frames'
        subprocess.run([str(java), '-cp', str(output), 'RouteProtocolTests', str(route_fixture)], check=True)
        assert route_fixture.read_bytes() == (root / 'tests/fixtures/nav4.frames').read_bytes(), 'Android NAV4 fixture changed'
        plan_fixture = output / 'nav4-plan.frames'
        subprocess.run([str(java), '-cp', str(output), 'RoutePlanTests', str(plan_fixture)], check=True)
        assert plan_fixture.read_bytes() == (root / 'tests/fixtures/nav4-plan.frames').read_bytes(), 'Android route plan fixture changed'
        scene_fixture = output / 'nav4-scene.frames'
        subprocess.run([str(java), '-cp', str(output), 'com.motonav.companion.BasemapTests', str(scene_fixture)], check=True)
        assert scene_fixture.read_bytes() == (root / 'tests/fixtures/nav4-scene.frames').read_bytes(), 'Android local map fixture changed'
        assert scene_fixture.with_name('nav4-map-updates.frames').read_bytes() == (root / 'tests/fixtures/nav4-map-updates.frames').read_bytes(), 'Android progressive map fixture changed'
        diagnostic_fixture = output / 'diag1.frames'
        subprocess.run([str(java), '-cp', str(output), 'BleDiagnosticProtocolTests', str(diagnostic_fixture)], check=True)
        assert diagnostic_fixture.read_bytes() == (root / 'tests/fixtures/diag1.frames').read_bytes(), 'Android DIAG1 fixture changed'
    extra_sources = {
        'display_qspi_test': ['components/motonav_display/qspi_panel.cpp'],
        'pixel_pack_test': ['components/motonav_display/pixel_pack.cpp'],
        'navigation_service_test': ['main/app/navigation_service.cpp'],
        'ble_transport_test': ['components/motonav_ble/ble_transport.cpp'],
        'diagnostic_protocol_test': ['components/motonav_diagnostics/format.cpp'],
        'diagnostic_bridge_test': ['components/motonav_diagnostics/format.cpp', 'components/motonav_diagnostics/serial_bridge.cpp'],
        'display_runtime_test': ['components/motonav_display/display.cpp', 'components/motonav_display/pixel_pack.cpp',
                                 'main/app/device_supervisor.cpp'],
    }
    for name in ['map_position_test', 'navigation_details_test', 'basemap_protocol_test', 'navigation_protocol_test', 'route_protocol_test', 'ble_channel_test', 'display_model_test', 'map_projection_test', 'map_raster_test',
                 'navigation_session_test', 'display_qspi_test', 'pixel_pack_test',
                 'navigation_service_test', 'notification_queue_test', 'ble_transport_test', 'display_runtime_test', 'diagnostic_protocol_test', 'diagnostic_bridge_test']:
        executable = output / (name + ('.exe' if os.name == 'nt' else ''))
        subprocess.run([args.cxx, '-std=c++17', '-Wall', '-Wextra', '-Werror',
                        *[f'-I{path}' for path in [root / 'components/motonav_core/include',
                            root / 'components/motonav_graphics/include', root / 'components/motonav_display',
                            root / 'components/motonav_graphics', root / 'components/motonav_display/include',
                            root / 'components/motonav_ble', root / 'components/motonav_ble/include',
                            root / 'components/motonav_diagnostics/include',
                            root / 'components/motonav_platform/include', root / 'main/app']],
                        *([f'-I{root / "tests/host/qspi_sdk"}'] if name == 'display_qspi_test' else []),
                        *([f'-I{root / "tests/host/diagnostic_sdk"}'] if name == 'diagnostic_bridge_test' else []),
                        *([f'-I{root / "tests/host/runtime_sdk"}'] if name in {
                            'navigation_service_test', 'notification_queue_test', 'ble_transport_test', 'display_runtime_test', 'diagnostic_bridge_test'} else []),
                        *([f'-I{root / "tests/host/ble_sdk"}'] if name == 'ble_transport_test' else []),
                        *([f'-I{root / "tests/host/display_sdk"}'] if name == 'display_runtime_test' else []),
                        str(root / 'tests/host' / (name + '.cpp')),
                        *[str(root / path) for path in extra_sources.get(name, [])],
                        '-o', str(executable)], check=True)
        if name == 'display_runtime_test':
            for mode in ['normal', 'timeout', 'submit', 'submit_legacy', 'control', 'brightness',
                         'brightness_timeout', 'metrics_timeout', 'start_fault', 'fps_lock_fault', 'fps_lock_race']:
                subprocess.run([str(executable), mode], check=True)
        else:
            subprocess.run([str(executable), *([str(position_fixture)] if name == 'map_position_test' else [str(details_fixture)] if name == 'navigation_details_test' else [str(scene_fixture),str(scene_fixture.with_name('nav4-map-updates.frames'))] if name == 'basemap_protocol_test' else [str(fixture)] if name == 'navigation_protocol_test' else [str(route_fixture),str(plan_fixture)] if name == 'route_protocol_test' else [str(route_fixture)] if name == 'navigation_service_test' else [str(diagnostic_fixture)] if name in {'diagnostic_protocol_test', 'diagnostic_bridge_test'} else [])], check=True)
