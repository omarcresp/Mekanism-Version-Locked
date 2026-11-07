#!/usr/bin/env python3
"""Inventory Mekanism modules and declared integrations from an official server export."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tomllib
import zipfile


def inventory(archive, target=None):
    report = {'fileId': 9431427, 'url': 'https://www.curseforge.com/minecraft/modpacks/all-the-mods-10/files/9431427',
              'sha256': hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest(), 'mods': []}
    if report['sha256'] != 'dfb34538715589f354c370101ffab3cbb2c3c8dcbc1dac6e382e8560b4a6db06':
        raise ValueError('server archive does not match the pinned ATM10 8.5 SHA-256')
    present = {}
    if target is not None:
        for path in sorted((target / 'mods').glob('*.jar')):
            with zipfile.ZipFile(path) as jar:
                if 'META-INF/neoforge.mods.toml' in jar.namelist():
                    data = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode())
                    for mod in data.get('mods', []):
                        present[mod['modId']] = path.name
    with zipfile.ZipFile(archive) as pack:
        jars = [name for name in pack.namelist() if '/mods/' in '/' + name and name.endswith('.jar')]
        report['serverJarCount'] = len(jars)
        for name in jars:
            with zipfile.ZipFile(io.BytesIO(pack.read(name))) as jar:
                metadata = next((p for p in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml') if p in jar.namelist()), None)
                if metadata is None:
                    continue
                data = tomllib.loads(jar.read(metadata).decode())
                declared = data.get('dependencies', {})
                dependencies = [d for entries in declared.values() for d in entries] if isinstance(declared, dict) else declared
                dependencies = [d for d in dependencies if isinstance(d, dict) and 'mekanism' in d.get('modId', '').lower()]
                if 'mek' in Path(name).name.lower() or dependencies:
                    report['mods'].append({'file': Path(name).name, 'mods': [
                        {'id': mod['modId'], 'version': mod.get('version'), 'name': mod.get('displayName')}
                        for mod in data.get('mods', [])], 'mekanismDependencies': dependencies})
    if target is not None:
        for entry in report['mods']:
            entry['targetMatches'] = {mod['id']: present.get(mod['id']) for mod in entry['mods']}
        report['interpretation'] = 'Metadata presence only; optional dependencies do not prove runtime integration, and absent parent mods may be outside Mekanism-specific parity.'
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--target', type=Path)
    args = parser.parse_args()
    report = inventory(args.baseline, args.target)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('\n'.join(mod['file'] for mod in report['mods']))


if __name__ == '__main__':
    main()
