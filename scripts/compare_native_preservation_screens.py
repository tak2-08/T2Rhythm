#!/usr/bin/env python3
"""Compare original and native-host application captures, excluding changing Android system bars."""
import argparse
import json
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', type=Path, required=True)
parser.add_argument('--candidate', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
before = {path.name: path for path in args.baseline.rglob('audit-*.png')}
after = {path.name: path for path in args.candidate.rglob('audit-*.png')}
assert len(before) == 7 and before.keys() == after.keys(), (sorted(before), sorted(after))
results = []
for name in sorted(before):
    a, b = Image.open(before[name]).convert('RGB'), Image.open(after[name]).convert('RGB')
    assert a.size == b.size, (name, a.size, b.size)
    width, height = a.size
    # Keep all application UI. Only the Android status/navigation bars change with capture time.
    first, last = int(height * .06), int(height * .93)
    left = a.tobytes()[first * width * 3:last * width * 3]
    right = b.tobytes()[first * width * 3:last * width * 3]
    difference = [abs(x - y) for x, y in zip(left, right)]
    mean = sum(difference) / len(difference)
    changed = sum(max(difference[i:i + 3]) > 12 for i in range(0, len(difference), 3)) / (len(difference) / 3)
    results.append(dict(name=name, width=width, height=height, meanChannelDifference=mean,
                        changedPixelFraction=changed, success=mean <= 3.0 and changed <= .03))
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(results, indent=2) + '\n')
for result in results: print(json.dumps(result))
assert all(result['success'] for result in results), 'Visual differences require inspection before PR; compare the retained original PNGs.'
