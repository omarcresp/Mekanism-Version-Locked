#!/usr/bin/env python3
"""Repair ATM11 0.14's Mekanism tooltip selectors in a locally owned pack copy."""
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


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('instance', type=Path)
    args = parser.parse_args()
    if not (args.instance / 'atm11-owned-test-instance').is_file():
        parser.error('only marked disposable client copies may be patched by this command')
    print(f'Updated {patch_tooltips(args.instance)} tooltip selectors')
