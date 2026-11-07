#!/usr/bin/env python3
"""Build a deterministic, resources-only platinum-processing POC for ATM11 0.14."""
import argparse
import hashlib
import copy
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
META = '''modLoader="lowcodefml"
loaderVersion="[1,)"
license="MIT"
[[mods]]
modId="atm11mekcompat"
version="0.1.0"
displayName="ATM11 Mekanism compatibility POC"
[[dependencies.atm11mekcompat]]
modId="mekanism"
type="required"
versionRange="[10.8,)"
side="BOTH"
[[dependencies.atm11mekcompat]]
modId="alltheores"
type="required"
versionRange="[4,)"
side="BOTH"
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mods', type=Path, required=True, help='ATM11 0.14.0-beta server mods directory')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/atm11/atm11-mekanism-compat-0.1.0.jar')
    args = parser.parse_args()
    recipes = {}
    for suffix, kind, tag, count, amount in [
        ('from_ore', 'enriching', 'ores/platinum', 1, 2),
        ('from_raw', 'enriching', 'raw_materials/platinum', 3, 4),
        ('from_raw_block', 'enriching', 'storage_blocks/raw_platinum', 1, 12),
        ('from_ingot', 'crushing', 'ingots/platinum', 1, 1),
    ]:
        recipes[f'data/atm11mekcompat/recipe/platinum/{suffix}.json'] = json.dumps({
            'type': f'mekanism:{kind}',
            'input': {'count': count, 'ingredient': f'#c:{tag}'},
            'output': {'count': amount, 'id': 'alltheores:platinum_dust'},
        }, indent=2) + '\n'
    # Keep recipe IDs/conditions and quantities; translate only the retired schema.
    provenance = []
    for pattern in ('arsmagicalegacy-*.jar', 'productivebees-*.jar'):
        candidates = list(args.mods.glob(pattern))
        assert len(candidates) == 1, f'Expected one pinned source for {pattern}'
        source = candidates[0]
        with zipfile.ZipFile(source) as jar:
            for name in jar.namelist():
                if '/recipe/' not in name or not name.endswith('.json'):
                    continue
                original = json.loads(jar.read(name))
                if not original.get('type', '').startswith('mekanism:'):
                    continue
                updated = copy.deepcopy(original)
                for old, new in [('mainOutput', 'main_output'), ('secondaryOutput', 'secondary_output'),
                                 ('secondaryChance', 'secondary_chance')]:
                    if old in updated:
                        updated[new] = updated.pop(old)
                for field in ('output', 'main_output', 'secondary_output'):
                    value = updated.get(field)
                    if isinstance(value, dict):
                        for old in ('item', 'pigment'):
                            if old in value:
                                assert 'id' not in value
                                value['id'] = value.pop(old)
                value = updated.get('input')
                if isinstance(value, dict) and 'neoforge:ingredient_type' in value:
                    updated['input'] = {'count': 1, 'ingredient': value}
                if updated != original:
                    recipes[name] = json.dumps(updated, indent=2) + '\n'
                    provenance.append({'source': source.name, 'recipe': name,
                                       'sourceSHA256': hashlib.sha256(jar.read(name)).hexdigest()})
    files = {'META-INF/neoforge.mods.toml': META, **recipes,
             'META-INF/poc-recipe-provenance.json': json.dumps(provenance, indent=2)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
        for name, content in sorted(files.items()):
            jar.writestr(zipfile.ZipInfo(name, (2026, 11, 6, 0, 0, 0)), content)
    print(args.output)
    print(hashlib.sha256(args.output.read_bytes()).hexdigest())
    print(f'{len(provenance)} legacy recipes repaired; 4 platinum processing recipes added')


if __name__ == '__main__':
    main()
