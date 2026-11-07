#!/usr/bin/env python3
"""Migrate ATM11 0.14 pack scripts, quests and fuel data for the Mekanism fork."""
import argparse
from pathlib import Path
import re
import json
from balance import apply_balance


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
    changes = 0
    # These are the published ATM10 8.5 fuels, converted from Joules to FE.
    # Normalize both legacy input and already-migrated RC1 overlays.
    fuels = {'mekanism:hydrogen': {'energy': 80, 'maxBurnPerTick': 256},
             'mekanism:ethene': {'energy': 2400, 'maxBurnPerTick': 256, 'burnTicksPerMB': 20}}
    for key, expected in fuels.items():
        current = data['values'].get(key)
        if current is not None and current != expected:
            data['values'][key] = expected
            changes += 1
    for key, value in data['values'].items():
        if 'burn_time' in value:
            ticks = value.pop('burn_time')
            scaled = ticks * value['energy'] * 2
            density, remainder = divmod(scaled, 5)
            if remainder or not 0 < density <= 2147483647:
                raise ValueError(f'fuel energy density cannot be represented in whole FE: {key}')
            value.update(energy=int(density), maxBurnPerTick=256, burnTicksPerMB=ticks)
            changes += 1
    if changes:
        path.write_text(json.dumps(data, indent=2) + '\n')
    return changes


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
    return {'atm10BalanceSettings': apply_balance(instance),
            'tooltipSelectors': patch_tooltips(instance),
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
