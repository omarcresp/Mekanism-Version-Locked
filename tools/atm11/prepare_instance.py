#!/usr/bin/env python3
"""Create a muted, isolated ATM11 client copy without accounts or player saves."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
from patch_pack import patch_pack

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='existing ATM11 minecraft directory')
    parser.add_argument('--target', type=Path, required=True, help='new disposable test directory')
    args = parser.parse_args()
    source, target = args.source.resolve(), args.target.resolve()
    if target.exists():
        parser.error('target must be new; existing instances are never overwritten')
    core = ROOT / 'build/libs/Mekanism-26.1.2-10.8.0.homebaked.jar'
    generators = ROOT / 'build/libs/MekanismGenerators-26.1.2-10.8.0.homebaked.jar'
    if not all(p.is_file() for p in (core, generators)):
        parser.error('build Core and Generators first')
    if not (source / 'mods').is_dir():
        parser.error('source must contain the pack mods directory')
    target.mkdir(parents=True)
    (target / 'atm11-owned-test-instance').write_text('Disposable client copy; no player saves or accounts.\n')
    for name in ('mods', 'config', 'kubegroovy', 'local', 'shaderpacks', 'resourcepacks'):
        if (source / name).is_dir():
            shutil.copytree(source / name, target / name)
    # This specific extra local build requires .124 and cannot load on the target .126.
    extra = target / 'mods/ancestrallies-0.1.0-alpha.1.jar'
    if extra.exists():
        extra.rename(extra.with_suffix('.jar.disabled'))
    # Remove the source instance's Core/Generators by mod ID metadata, preserving other addons.
    import zipfile
    import tomllib
    for path in (target / 'mods').glob('*.jar'):
        with zipfile.ZipFile(path) as jar:
            if 'META-INF/neoforge.mods.toml' not in jar.namelist():
                continue
            metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode())
            if any(mod.get('modId') in ('mekanism', 'mekanismgenerators', 'atm11mekcompat') for mod in metadata.get('mods', [])):
                path.rename(path.with_suffix('.jar.disabled'))
    for artifact in (core, generators):
        shutil.copy2(artifact, target / 'mods' / artifact.name)
    subprocess.run([sys.executable, str(ROOT / 'tools/atm11/build_compat.py'), '--mods', str(target / 'mods'),
                    '--output', str(target / 'mods/atm11-mekanism-compat-0.1.0.jar')], check=True)
    patch_pack(target)
    # Create this before the first client launch so Default Options cannot initialize audible defaults.
    (target / 'options.txt').write_text('soundCategory_master:0.0\n')
    print(target)


if __name__ == '__main__':
    main()
