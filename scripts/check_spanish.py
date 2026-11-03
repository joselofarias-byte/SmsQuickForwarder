#!/usr/bin/env python3
"""Check resource parity and prevent untranslated Chinese text outside matching data."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
res = root / 'app/src/main/res'
def strings(locale):
    nodes = ET.parse(res / locale / 'strings.xml').getroot()
    names = [node.attrib['name'] for node in nodes]
    assert len(names) == len(set(names)), f'Duplicate names: {locale}'
    return {node.attrib['name']: node.text or '' for node in nodes}
default = strings('values')
spanish = strings('values-es')
assert default.keys() == spanish.keys(), 'Spanish/default key mismatch'
assert default == spanish, 'Fallback language must remain Spanish'
assert not re.search(r'[\u3400-\u9fff]', '\n'.join(spanish.values()))
for path in (root / 'app/src/main/java').rglob('*.kt'):
    for number, line in enumerate(path.read_text().splitlines(), 1):
        # Chinese keywords intentionally remain inside these five matching templates.
        if path.name == 'RuleEditActivity.kt' and re.search(r'"(?:verification|bank|delivery|bill|login)" ->', line):
            continue
        assert not re.search(r'[\u3400-\u9fff]', line), f'{path}:{number}: untranslated Chinese'
    for key in re.findall(r'R\.string\.(\w+)', path.read_text()):
        assert key in default, f'{path}: missing resource {key}'
changelog = json.loads((root / 'app/src/main/assets/changelog.json').read_text())
assert not re.search(r'[\u3400-\u9fff]', json.dumps(changelog, ensure_ascii=False))
print(f'OK: {len(spanish)} Spanish strings, resource references, Kotlin text and offline changelog')
