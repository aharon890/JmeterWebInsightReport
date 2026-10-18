#!/usr/bin/env python3
"""Strip the run-specific parts of a report artifact so two runs of one input compare equal.

What is dropped, and why each one:

  runId          a fingerprint of the run, designed to change. Its contract is "same JTL =>
                 same runId; distinct executions => distinct runId" -- pinning it would assert
                 that a value built to change never changes, which is the opposite of the
                 contract. It is the one field that MUST be excluded from any such comparison.
  generatedAt    wall clock.
  artifacts.*    absolute paths, which differ per machine and per output directory. The
                 basename is kept, because WHICH files were written is part of the contract.
  JUnit attrs    timestamp, hostname and time on <testsuite>/<testcase>.

What is deliberately NOT dropped: every measured number. They come from a committed, fixed JTL,
so they are constants -- and a change that moves one is exactly what this exists to make loud.
Nothing is sorted either: key order is part of the contract (the serializers use LinkedHashMap
on purpose), and a reordering is a change a consumer can see.
"""
import json
import re
import sys
from collections import OrderedDict

DROP_KEYS = {'runId', 'generatedAt'}


def canonical_json(text):
    doc = json.loads(text, object_pairs_hook=OrderedDict)
    return json.dumps(_walk(doc, []), indent=2, ensure_ascii=False) + '\n'


def _walk(node, path):
    if isinstance(node, OrderedDict):
        out = OrderedDict()
        for k, v in node.items():
            if k in DROP_KEYS:
                continue
            # Absolute paths: keep the basename, drop the machine. `path` under artifacts says
            # WHICH files were written, which is contract; the directory they landed in is the
            # invocation, and outputDirectory has no basename worth keeping at all. Without
            # this the baselines only ever matched on the machine that generated them.
            elif k == 'outputDirectory' and isinstance(v, str):
                out[k] = '<outputDir>'
            elif k == 'path' and 'artifacts' in path and isinstance(v, str):
                out[k] = re.split(r'[\\/]', v)[-1]
            else:
                out[k] = _walk(v, path + [k])
        return out
    if isinstance(node, list):
        return [_walk(v, path) for v in node]
    return node


def canonical_xml(text):
    # Attribute-level, not a parse: the writer emits these by hand and the golden should notice
    # if that ever changes shape, so reformatting through a parser would hide the very thing
    # being watched.
    text = re.sub(r'\s(timestamp|hostname|time)="[^"]*"', '', text)
    text = re.sub(r'\srunId="[^"]*"', '', text)
    return text


def main():
    if len(sys.argv) != 3:
        print('usage: canonicalise-artifact.py <in.json|in.xml> <out>', file=sys.stderr)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, encoding='utf-8') as fh:
        text = fh.read()
    out = canonical_json(text) if src.endswith('.json') else canonical_xml(text)
    # Written here rather than through a shell redirect, and explicitly UTF-8 with LF.
    # `> file` goes through the console codepage on Windows, which turned every em-dash in a
    # finding message into mojibake -- so the baselines were corrupt for exactly the strings
    # this stage exists to watch, and byte-identical corruption would have compared equal
    # forever on that machine and differed on any other.
    with open(dst, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
