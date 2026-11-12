"""Make am instrument's textual result a fail-closed CI result; adb itself may return zero on failure."""
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

def report(text, destination):
    tests = []
    status = {}
    expected = None
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator:
                status[key] = value
                if key == 'numtests': expected = int(value)
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.partition(':')[2])
            if code != 1 and 'test' in status and 'class' in status:
                tests.append((status['class'], status['test'], code, status.get('stack', '')))
            status = {}
    completed = re.search(r'^OK \((\d+) tests?\)\s*$', text, re.MULTILINE)
    failed = [test for test in tests if test[2] != 0]
    success = (completed is not None and expected is not None and expected > 0
        and len(tests) == expected == int(completed.group(1))
        and len({(t[0], t[1]) for t in tests}) == expected and not failed
        and 'INSTRUMENTATION_CODE: -1' in text
        and not re.search(r'INSTRUMENTATION_FAILED|Process crashed|FAILURES!!!', text))
    suite = ET.Element('testsuite', name='MotoNavAndroidRuntime', tests=str(len(tests)), failures=str(len(failed)+(not success and not failed)))
    for classname, name, code, stack in tests:
        case = ET.SubElement(suite, 'testcase', classname=classname, name=name)
        if code != 0: ET.SubElement(case, 'failure', message=f'Instrumentation status {code}').text = stack
    if not success and not failed:
        ET.SubElement(ET.SubElement(suite, 'testcase', name='instrumentation_completion'), 'failure', message='Missing, skipped, duplicate or incomplete instrumentation results')
    ET.ElementTree(suite).write(destination, encoding='utf-8', xml_declaration=True)
    return success, len(tests)

if __name__ == '__main__':
    passed, count = report(Path(sys.argv[1]).read_text(encoding='utf-8'), sys.argv[2])
    print(f'Android instrumentation: {count} completed; ' + ('PASS' if passed else 'FAIL'))
    sys.exit(0 if passed else 1)
