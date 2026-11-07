#!/usr/bin/env python3
"""Migrate ATM11 0.14 pack scripts, quests and fuel data for the Mekanism fork."""
import argparse
from pathlib import Path
import re
import json
import zipfile


def patch_tooltips(instance: Path):
    script = instance / 'kubegroovy/client_scripts/tooltips/mekanism.groovy'
    if not script.is_file():
        return 0
    original = script.read_text()
    # Groovy slashy literals are strings; the ~ operator creates a Pattern.
    # KubeGroovy matches entire IDs, so retain the prefix with a trailing wildcard.
    updated, count = re.subn(r'\.add\(/(mekanism(?:generators)?:[a-z_]+)_/,',
                             r'.add(~/\1_.*/,', original)
    if count:
        script.write_text(updated)
    return count


def patch_optional_chemical_items(instance: Path):
    path = instance / 'kubegroovy/client_scripts/recipe_viewer/hide_items.groovy'
    if not path.is_file():
        return 0
    original = path.read_text()
    updated, count = re.subn(
        r'(?m)^(\s*)allthemods\.remove\("(extradisks:infinite_chemical_storage_(?:block|disk|part))"\)$',
        r'\1if ($Item.exists("\2")) allthemods.remove("\2")', original)
    if count:
        path.write_text(updated)
    return count


def enable_quests(instance: Path):
    changed = 0
    for chapter in ('mekanism', 'mekanism_reactors'):
        path = instance / f'config/ftbquests/quests/chapters/{chapter}.json5'
        if path.is_file():
            original = path.read_text()
            updated, count = re.subn(r'(?m)^  always_invisible: true,', '  always_invisible: false,', original)
            if count:
                path.write_text(updated)
                changed += count
    return changed


def remove_unavailable_quest_gates(instance: Path):
    changed = 0
    # These hidden "NOT HERE YET" checkmarks disable the entire quest chains.
    for chapter, gate in (('mekanism', '132777D37FD5E38B'),
                          ('mekanism_reactors', '48195ED3F9E729BE')):
        path = instance / f'config/ftbquests/quests/chapters/{chapter}.json5'
        if not path.is_file():
            continue
        original = path.read_text()
        def migrate(match):
            nonlocal changed
            updated, count = re.subn(r'(?m)^\s*"' + gate + r'",?\s*$', '', match.group())
            changed += count
            return updated
        updated = re.sub(r'dependencies: \[.*?\]', migrate, original, flags=re.DOTALL)
        if updated != original:
            path.write_text(updated)
    return changed


def migrate_fuel(instance: Path):
    path = instance / 'kubegroovy/dynamic_data/mekanism/data_maps/mekanism/chemical/chemical_attribute_fuel.json'
    if not path.is_file():
        return 0
    data = json.loads(path.read_text(encoding='utf-8-sig'))
    legacy = {key: value for key, value in data['values'].items() if 'burn_time' in value}
    if not legacy:
        return 0
    resource = 'data/mekanism/data_maps/mekanism/chemical/chemical_attribute_fuel.json'
    defaults = []
    for jar_path in (instance / 'mods').glob('Mekanism-*.jar'):
        with zipfile.ZipFile(jar_path) as jar:
            if resource in jar.namelist():
                defaults.append(json.loads(jar.read(resource))['values'])
    if len(defaults) != 1:
        raise ValueError('fuel migration requires exactly one current Mekanism Core JAR')
    for key, value in legacy.items():
        density = value['burn_time'] * value['energy']
        if not 0 < density <= 2147483647:
            raise ValueError(f'fuel energy density cannot be represented: {key}')
        value.pop('burn_time')
        value['energy'] = density
        value['maxBurnPerTick'] = defaults[0][key]['maxBurnPerTick']
    path.write_text(json.dumps(data, indent=2) + '\n')
    return len(legacy)


def migrate_creative_energy(instance: Path):
    path = instance / 'kubegroovy/server_scripts/progression/atm_star_creative.groovy'
    if not path.is_file():
        return 0
    original = path.read_text()
    old = 'mekanism:energy={energy_containers:[L;9223372036854775807L]}'
    count = original.count(old)
    if count:
        path.write_text(original.replace(old, 'mekanism:energy=9223372036854775807L'))
    return count


def patch_pack(instance: Path):
    return {'tooltipSelectors': patch_tooltips(instance),
            'optionalChemicalItemSelectors': patch_optional_chemical_items(instance), 'questChapters': enable_quests(instance),
            'unavailableQuestDependencies': remove_unavailable_quest_gates(instance),
            'chemicalFuels': migrate_fuel(instance), 'creativeEnergyComponents': migrate_creative_energy(instance)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('instance', type=Path)
    args = parser.parse_args()
    if not any((args.instance / marker).is_file() for marker in ('atm11-owned-test-instance', 'poc-owned-world')):
        parser.error('only marked disposable pack copies may be patched by this command')
    print(patch_pack(args.instance))
