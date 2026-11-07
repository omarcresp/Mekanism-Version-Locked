#!/usr/bin/env python3
"""Compare a pinned ATM10 export with an isolated ATM11 instance without copying pack content."""
import argparse
import hashlib
import json
from pathlib import Path
import tomllib
import zipfile


def flatten(data, prefix=''):
    for key, value in data.items():
        name = prefix + key
        if isinstance(value, dict):
            yield from flatten(value, name + '.')
        else:
            yield name, value


def audit(archive, target):
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != 'ea56a46b478b634ac538dfdd4c7175b5e58f6f3e0df67d25373ff601c79b49ac':
        raise ValueError('baseline archive does not match the pinned ATM10 8.5 SHA-256')
    with zipfile.ZipFile(archive) as pack:
        manifest = json.loads(pack.read('manifest.json'))
        assert manifest['name'] == 'All the Mods 10' and manifest['version'] == '8.5', 'expected pinned ATM10 8.5 export'
        prefix = manifest['overrides'] + '/'
        configs = []
        references = []
        for name in sorted(pack.namelist()):
            if name.endswith('/'):
                continue
            relative = name.removeprefix(prefix)
            if relative.startswith('config/Mekanism/') and relative.endswith('.toml'):
                baseline = dict(flatten(tomllib.loads(pack.read(name).decode('utf-8-sig'))))
                path = target / relative
                actual = dict(flatten(tomllib.loads(path.read_text(encoding='utf-8-sig')))) if path.exists() else {}
                configs.append({'file': relative, 'targetPresent': path.exists(), 'differences': [
                    {'key': key, 'atm10': value, 'atm11': actual.get(key), 'targetKeyPresent': key in actual}
                    for key, value in baseline.items() if key not in actual or actual[key] != value]})
            if 'mekanism' in relative.lower() or 'mekanism' in pack.read(name).decode('utf-8', errors='ignore').lower():
                references.append({'file': relative, 'sha256': hashlib.sha256(pack.read(name)).hexdigest()})
        return {'baseline': {'pack': manifest['name'], 'version': manifest['version'], 'minecraft': manifest['minecraft'],
                             'fileId': 9431424, 'url': 'https://www.curseforge.com/minecraft/modpacks/all-the-mods-10/files/9431424',
                             'sha256': digest, 'modCount': len(manifest['files'])},
                'configComparison': configs, 'referenceFiles': references,
                'interpretation': 'Differences require semantic review; energy units, removed settings and intentional target behavior may differ.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--target', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.baseline, args.target)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'configs': len(report['configComparison']), 'references': len(report['referenceFiles']),
                      'differentSettings': sum(len(c['differences']) for c in report['configComparison'])}))


if __name__ == '__main__':
    main()
