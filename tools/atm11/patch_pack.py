#!/usr/bin/env python3
"""Repair ATM11 0.14 Mekanism tooltip selectors and reveal its existing quests."""
import argparse
from pathlib import Path
import re


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


def patch_pack(instance: Path):
    return {'tooltipSelectors': patch_tooltips(instance), 'questChapters': enable_quests(instance)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('instance', type=Path)
    args = parser.parse_args()
    if not any((args.instance / marker).is_file() for marker in ('atm11-owned-test-instance', 'poc-owned-world')):
        parser.error('only marked disposable pack copies may be patched by this command')
    print(patch_pack(args.instance))
