#!/usr/bin/env python3
"""Verify fixed-seed reproducibility using both manifest metadata and live file hashes."""
import argparse
import hashlib
import json
import sys
from pathlib import Path


def actual_hashes(root: Path, manifest: dict):
    actual = {}
    for name, metadata in manifest.get('files', {}).items():
        path = root / name
        if not path.is_file():
            raise ValueError(f"missing generated file: {path}")
        content = path.read_bytes()
        digest = hashlib.sha256(content).hexdigest()
        if digest != metadata.get('sha256') or len(content) != metadata.get('bytes'):
            raise ValueError(f"manifest does not match actual file: {path}")
        actual[name] = {'sha256': digest, 'bytes': len(content)}
    return actual


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('first')
    parser.add_argument('second')
    args = parser.parse_args()
    first, second = Path(args.first), Path(args.second)
    try:
        m1 = json.loads((first / 'source_manifest.json').read_text(encoding='utf-8'))
        m2 = json.loads((second / 'source_manifest.json').read_text(encoding='utf-8'))
        for key in ('seed', 'scale', 'baseline_counts', 'weights', 'risk_bands'):
            if m1.get(key) != m2.get(key):
                print(f'FAIL: manifest field differs: {key}')
                return 1
        h1, h2 = actual_hashes(first, m1), actual_hashes(second, m2)
        if h1.keys() != h2.keys():
            print('FAIL: generated file sets differ')
            return 1
        for name in sorted(h1):
            if h1[name] != h2[name]:
                print(f'FAIL: generated file differs: {name}')
                return 1
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f'FAIL: {exc}')
        return 1
    print('PASS: seed, scale, row counts, manifest integrity, and SHA-256 checksums match.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
