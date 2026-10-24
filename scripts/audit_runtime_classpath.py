#!/usr/bin/env python3
"""Audit DEX class ownership and desugared-library references without executing feature code."""
import argparse
import json
from pathlib import Path
import struct
import zipfile


def read_dex(data):
    if not data.startswith(b'dex\n'):
        raise ValueError('Invalid DEX header')
    def integer(offset):
        return struct.unpack_from('<I', data, offset)[0]
    def uleb(offset):
        value = shift = 0
        for _ in range(5):
            byte = data[offset]; offset += 1
            value |= (byte & 127) << shift
            if byte < 128: return value, offset
            shift += 7
        raise ValueError('Invalid DEX integer')
    strings = []
    for index in range(integer(56)):
        _, start = uleb(integer(integer(60) + 4 * index))
        strings.append(data[start:data.index(0, start)].decode('utf-8', errors='replace'))
    types = [strings[integer(integer(68) + 4 * index)] for index in range(integer(64))]
    prototypes = []
    for index in range(integer(72)):
        offset = integer(76) + index * 12
        result, params = integer(offset + 4), integer(offset + 8)
        arguments = [] if not params else [types[struct.unpack_from('<H', data, params + 4 + i * 2)[0]] for i in range(integer(params))]
        prototypes.append('(' + ''.join(arguments) + ')' + types[result])
    methods = []
    for index in range(integer(88)):
        owner, prototype, name = struct.unpack_from('<HHI', data, integer(92) + 8 * index)
        methods.append((types[owner], strings[name], prototypes[prototype]))
    definitions = set()
    classes = {}
    for index in range(integer(96)):
        offset = integer(100) + index * 32
        owner, parent, class_data = integer(offset), integer(offset + 8), integer(offset + 24)
        classes[types[owner]] = None if parent == 0xffffffff else types[parent]
        if not class_data: continue
        counts = []
        for _ in range(4):
            value, class_data = uleb(class_data); counts.append(value)
        for _ in range(counts[0] + counts[1]):
            _, class_data = uleb(class_data); _, class_data = uleb(class_data)
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                difference, class_data = uleb(class_data); method_index += difference
                _, class_data = uleb(class_data); _, class_data = uleb(class_data)
                definitions.add(methods[method_index])
    return classes, definitions, set(methods)


def read_archive(path):
    classes, definitions, references = {}, set(), set()
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.startswith('classes') and name.endswith('.dex') and '/' not in name:
                current, defined, referenced = read_dex(archive.read(name))
                if classes.keys() & current.keys(): raise ValueError('Duplicate classes inside one DEX archive')
                classes.update(current); definitions.update(defined); references.update(referenced)
    if not classes: raise ValueError('No DEX classes')
    return classes, definitions, references


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--feature-dex', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    base, base_methods, _ = read_archive(args.base)
    feature, feature_methods, references = read_archive(args.feature_dex)
    for name in ('chromahub/rhythm/module/RhythmFeatureModule', 'chromahub/rhythm/app/shared/data/model/AppSettings',
                 'chromahub/rhythm/app/features/local/presentation/viewmodel/MusicViewModel'):
        descriptor = 'L' + name + ';'
        if descriptor in base or descriptor not in feature:
            parser.error('Music implementation was linked into the base or is absent from the feature: ' + name)
    classes = dict(base, **feature)
    methods = base_methods | feature_methods
    missing = set()
    for owner, name, signature in references:
        if not owner.startswith('Lj$/'): continue
        current = owner
        while current is not None and (current, name, signature) not in methods:
            current = classes.get(current)
        if current is None: missing.add((owner, name, signature))
    summary = dict(baseClasses=len(base), featureClasses=len(feature),
                   desugaredReferences=sum(owner.startswith('Lj$/') for owner, _, _ in references),
                   missingDesugaredMethods=[list(item) for item in sorted(missing)])
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({key: value for key, value in summary.items() if key != 'missingDesugaredMethods'}, sort_keys=True))
    if missing: parser.error(f'{len(missing)} desugared methods are absent from the base/feature class path; see the audit artifact')


if __name__ == '__main__':
    main()
