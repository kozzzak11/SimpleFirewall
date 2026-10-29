"""Capture both bottom tabs using their observed accessibility bounds."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

def adb(*args):
    return subprocess.check_output(['adb', *args])

def hierarchy():
    adb('shell', 'uiautomator', 'dump', '/sdcard/sf-ui.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/sf-ui.xml'))

def find(root, description):
    return next((n for n in root.iter('node') if n.get('content-desc') == description), None)

def wait_for(description, timeout=20):
    deadline = time.time() + timeout
    root = None
    while time.time() < deadline:
        try:
            root = hierarchy()
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(1)
            continue
        node = find(root, description)
        if node is not None:
            return root, node
        time.sleep(1)
    shown = []
    if root is not None:
        shown = [n.get('content-desc') or n.get('text') or '' for n in root.iter('node')]
        shown = [s for s in shown if s][:30]
    log = ''
    try:
        log = adb('logcat', '-d', '-t', '80', 'AndroidRuntime:E', '*:S').decode('utf-8', 'replace')
    except subprocess.CalledProcessError as error:
        log = error.output.decode('utf-8', 'replace') if error.output else ''
    raise SystemExit('UI missing %s. Visible: %s\n%s' % (description, shown, log))

adb('shell', 'appops', 'set', 'com.kozzzak.simplefirewall', 'GET_USAGE_STATS', 'allow')
folder = pathlib.Path('app/build/ui')
folder.mkdir(parents=True, exist_ok=True)
for label, filename in [('Firewall', 'firewall.png'), ('Приложения', 'apps.png'), ('Трафик', 'traffic.png')]:
    _, node = wait_for('Вкладка ' + label)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)
    (folder / filename).write_bytes(adb('exec-out', 'screencap', '-p'))
