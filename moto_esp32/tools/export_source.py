"""Export a clean firmware source tree, excluding tests unless --with-tests is set.

The destination must not exist. No build outputs, credentials, NVS, flash backups
or managed dependencies are exported. Useful for proving test removal builds.
"""
import argparse
from pathlib import Path
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('destination', type=Path)
parser.add_argument('--with-tests', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
destination = args.destination.resolve()
destination.mkdir(parents=True, exist_ok=False)
for name in ['main', 'components', 'tools', *(['tests'] if args.with_tests else [])]:
    shutil.copytree(root / name, destination / name, ignore=shutil.ignore_patterns('__pycache__', '*.pyc', '*.log'))
for name in ['CMakeLists.txt', 'sdkconfig.defaults', 'partitions.csv', 'dependencies.lock', '.gitignore', '.gitattributes']:
    shutil.copy2(root / name, destination / name)
for doc in root.glob('*.md'):
    shutil.copy2(doc, destination / doc.name)
license_file = root / 'LICENSE'
if not license_file.is_file():
    license_file = root.parent / 'LICENSE'
if license_file.is_file():
    shutil.copy2(license_file, destination / 'LICENSE')
print(f'Exported {destination}; tests included: {args.with_tests}')
