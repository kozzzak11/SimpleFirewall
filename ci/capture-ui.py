"""Capture both bottom tabs using their observed accessibility bounds."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

def adb(*args):
    return subprocess.check_output(['adb', *args])

adb('shell', 'appops', 'set', 'com.kozzzak.simplefirewall', 'GET_USAGE_STATS', 'allow')
folder = pathlib.Path('app/build/ui')
folder.mkdir(parents=True, exist_ok=True)
for label, filename in [('Firewall', 'firewall.png'), ('Приложения', 'apps.png'), ('Трафик', 'traffic.png')]:
    adb('shell', 'uiautomator', 'dump', '/sdcard/sf-ui.xml')
    root = ET.fromstring(adb('exec-out', 'cat', '/sdcard/sf-ui.xml'))
    node = next(n for n in root.iter('node') if n.get('content-desc') == 'Вкладка ' + label)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1)
    (folder / filename).write_bytes(adb('exec-out', 'screencap', '-p'))
