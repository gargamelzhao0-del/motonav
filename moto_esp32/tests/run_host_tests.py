"""Run firmware tests with a host C++ compiler; no device or Android SDK is needed."""
import argparse
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cxx', default=os.environ.get('CXX', 'g++'))
parser.add_argument('--android-source', type=Path, help='Optionally regenerate frames from this android-recovery checkout')
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='motonav-firmware-tests-') as directory:
    output = Path(directory)
    fixture = root / 'tests' / 'nav3.frames'
    if args.android_source:
        java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        source = args.android_source.resolve()
        classes = ['Simulation', 'Protocol', 'NavigationFrame', 'NavigationProtocol']
        sources = [source / 'app/src/main/java/com/motonav/companion' / (name + '.java') for name in classes]
        sources.append(source / 'tests/NavigationProtocolTests.java')
        subprocess.run([str(java), '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8',
                        '-d', str(output), *map(str, sources)], check=True)
        fixture = output / 'nav3.frames'
        subprocess.run([str(java), '-cp', str(output), 'NavigationProtocolTests', str(fixture)], check=True)
        assert fixture.read_bytes() == (root / 'tests/nav3.frames').read_bytes(), 'Android protocol fixture changed'
    for name in ['navigation_protocol_test', 'display_model_test']:
        executable = output / (name + ('.exe' if os.name == 'nt' else ''))
        subprocess.run([args.cxx, '-std=c++17', '-Wall', '-Wextra', '-Werror',
                        str(root / 'tests' / (name + '.cpp')), '-o', str(executable)], check=True)
        subprocess.run([str(executable), *([str(fixture)] if name == 'navigation_protocol_test' else [])], check=True)
