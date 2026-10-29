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

def dismiss_anr(root):
    labels = []
    for n in root.iter('node'):
        labels.append(n.get('text') or '')
        labels.append(n.get('content-desc') or '')
    blob = ' '.join(labels)
    if "isn't responding" not in blob and 'не отвечает' not in blob:
        return False
    # Quickstep (the launcher) ANRs under uiautomator on the emulator and covers the app.
    system = 'Quickstep' in blob or 'Launcher' in blob
    order = ('Close app', 'Закрыть приложение', 'Wait', 'Подождать') if system else ('Wait', 'Подождать', 'Close app', 'Закрыть приложение')
    for want in order:
        node = next((n for n in root.iter('node') if n.get('text') == want or n.get('content-desc') == want), None)
        if node is None or not node.get('bounds'):
            continue
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(1)
        adb('shell', 'am', 'start', '-n', 'com.kozzzak.simplefirewall/.MainActivity')
        return True
    return False

def wait_for(description, timeout=40):
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
        dismiss_anr(root)
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
