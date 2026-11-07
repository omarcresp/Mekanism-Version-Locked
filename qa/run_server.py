#!/usr/bin/env python3
"""Run one marked, disposable ATM11 server QA phase and verify its real result."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--phase', choices=('seed', 'restart'), required=True)
    args = parser.parse_args()
    server = args.server.resolve()
    output = args.output.resolve()
    if not (server / 'poc-owned-world').is_file():
        parser.error('server must have the disposable-world ownership marker poc-owned-world')
    if args.phase == 'seed' and (server / 'seed-result.json').exists():
        parser.error('seed requires a fresh disposable world and no previous seed-result.json')
    if args.phase == 'restart':
        saved_seed = json.loads((server / 'seed-result.json').read_text())
        recorded_seed = json.loads((output / 'seed-result.json').read_text())
        assert saved_seed == recorded_seed and saved_seed['success'], 'seed evidence does not belong to this server world'
    output.mkdir(parents=True, exist_ok=True)
    prefix = output / args.phase
    inputs = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((server / 'mods').glob('*.jar'))}
    if args.phase == 'restart':
        assert json.loads((output / 'seed-inputs.json').read_text()) == inputs, 'mod files changed between JVMs'
    prefix.with_name(prefix.name + '-inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
    command = [args.java, '-Xms1G', '-Xmx6G', '@libraries/net/neoforged/neoforge/26.1.2.126/unix_args.txt', 'nogui']
    start = time.monotonic()
    process = subprocess.Popen(command, cwd=server, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, start_new_session=True)
    report = {'pid': process.pid, 'phase': args.phase, 'command': command}
    def record():
        with prefix.with_suffix('.log').open('w') as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                if 'ATM11_POC' in line or 'Done (' in line:
                    print(line.rstrip(), flush=True)
    reader = threading.Thread(target=record)
    reader.start()
    try:
        report['exitCode'] = process.wait(timeout=900)
    except subprocess.TimeoutExpired:
        report['timeout'] = True
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=30)
    reader.join(timeout=10)
    report['seconds'] = round(time.monotonic() - start, 2)
    prefix.with_name(prefix.name + '-process.json').write_text(json.dumps(report, indent=2) + '\n')
    assert report.get('exitCode') == 0 and not report.get('timeout'), report
    result = json.loads((server / (args.phase + '-result.json')).read_text())
    assert result['pid'] == process.pid and result['success'], result
    log_text = prefix.with_suffix('.log').read_text()
    assert 'Error parsing recipe' not in log_text, 'recipe decoding errors remain'
    assert "Error in 'ServerEvents.recipes'" not in log_text, 'pack recipe event failed'
    assert 'Could not read data map of type mekanism:' not in log_text, 'Mekanism data map failed'
    if args.phase == 'restart':
        assert json.loads((output / 'seed-result.json').read_text())['pid'] != result['pid']
    prefix.with_name(prefix.name + '-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'success': True, 'phase': args.phase, 'assertions': len(result['checks']), 'seconds': report['seconds']}))


if __name__ == '__main__':
    main()
