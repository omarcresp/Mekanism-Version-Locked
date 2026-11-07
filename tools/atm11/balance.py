"""Apply the pinned ATM10 electrical balance to a disposable pack copy."""
import json
from pathlib import Path
import re
import tomllib

PROFILE = Path(__file__).with_name('atm10-8.5-balance.json')


def rewrite_config(original, values):
    parsed = tomllib.loads(original)
    sections = {}
    for path, value in values.items():
        section, _, key = path.rpartition('.')
        sections.setdefault(section, {})[key] = value
    current_section = ''
    seen = set()
    visited = {''}
    lines = []
    changes = 0

    def flush():
        nonlocal changes
        for key, value in sections.get(current_section, {}).items():
            if (current_section, key) not in seen:
                lines.append(f'{key} = {json.dumps(value)}')
                seen.add((current_section, key))
                changes += 1

    for line in original.splitlines():
        header = re.fullmatch(r'\s*\[([\w.]+)\]\s*(?:#.*)?', line)
        if header:
            flush()
            current_section = header[1]
            visited.add(current_section)
        setting = re.fullmatch(r'(\s*)(\w+)(\s*=\s*)([^#]*)(#.*)?', line)
        if setting and setting[2] in sections.get(current_section, {}):
            key = setting[2]
            seen.add((current_section, key))
            expected = sections[current_section][key]
            actual = parsed
            for part in filter(None, current_section.split('.')):
                actual = actual[part]
            if type(actual[key]) is not type(expected) or actual[key] != expected:
                line = setting[1] + key + setting[3] + json.dumps(expected)
                if setting[5]:
                    line += ' ' + setting[5]
                changes += 1
        lines.append(line)
    flush()
    for section in (name for name in sections if name not in visited):
        current_section = section
        lines.extend(['', f'[{section}]'])
        flush()
    return '\n'.join(lines) + '\n', changes


def apply_balance(instance):
    profile = json.loads(PROFILE.read_text())
    changes = 0
    for filename, values in profile['configs'].items():
        path = instance / 'config/Mekanism' / filename
        original = path.read_text() if path.exists() else ''
        updated, count = rewrite_config(original, values)
        if count:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(updated)
        changes += count
    return changes
