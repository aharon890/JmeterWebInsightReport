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
import hashlib
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


# The page's server chart data (Stage 6.10b). The .json and .xml are the verdicts; what the Server
# tab DRAWS - which instances, how many points survived thinning, the numbers Java ships beside a
# capped copy (judgedMax, judgedActuals, judgedGap) - was compared only by petstore's gitignored
# cmp.cjs. This pulls chartData out of the report and summarises every server series, so a change
# that moves what the page draws on an unedited input fails R06 as a verdict change does.

def _js_string_at(text, start):
    """Decode the JS string literal whose opening quote is text[start]; Thymeleaf's JS escapes."""
    quote = text[start]
    out = []
    i = start + 1
    simple = {'n': '\n', 'r': '\r', 't': '\t', 'b': '\b', 'f': '\f', 'v': '\v', '0': '\0'}
    while i < len(text):
        c = text[i]
        if c == quote:
            # Lone surrogates from a 😀 pair become one character again.
            return ''.join(out).encode('utf-16', 'surrogatepass').decode('utf-16')
        if c == '\\':
            n = text[i + 1]
            if n == 'u':
                out.append(chr(int(text[i + 2:i + 6], 16)))
                i += 6
                continue
            if n == 'x':
                out.append(chr(int(text[i + 2:i + 4], 16)))
                i += 4
                continue
            out.append(simple.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    raise ValueError('unterminated chartData string')


def _instance_summary(inst):
    points = inst.get('points') or []
    values = [p[1] for p in points if p[1] is not None]
    out = OrderedDict()
    for k, v in inst.items():
        if k != 'points':
            out[k] = v
    out['shipped'] = len(points)
    out['first'] = points[0] if points else None
    out['last'] = points[-1] if points else None
    out['min'] = min(values) if values else None
    out['max'] = max(values) if values else None
    # Every shipped point, as one hash: the summary above cannot see a point moved in the middle.
    out['pointsSha'] = hashlib.sha256(json.dumps(points, separators=(',', ':')).encode('utf-8')).hexdigest()[:16]
    return out


def canonical_server(text):
    """The report's server chart data, summarised; None when the page carries no server series."""
    m = re.search(r'var chartData = (["\'])', text)
    if not m:
        raise ValueError('no inline chartData in the page')
    data = json.loads(_js_string_at(text, m.start(1)), object_pairs_hook=OrderedDict)
    server = data.get('serverMetrics') or {}
    series = server.get('series') or []
    if not series:
        return None
    out = OrderedDict()
    out['window'] = server.get('window')
    out['series'] = []
    for s in series:
        entry = OrderedDict()
        for k, v in s.items():
            if k != 'instances':
                entry[k] = v
        entry['instances'] = [_instance_summary(i) for i in (s.get('instances') or [])]
        out['series'].append(entry)
    return json.dumps(out, indent=2, ensure_ascii=False) + '\n'


def main():
    if len(sys.argv) not in (3, 4):
        print('usage: canonicalise-artifact.py <in.json|in.xml|in.html> <out> [--volatile-collection]', file=sys.stderr)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    # A collection made in-process from a CSV (G10) is stamped with the file's own time, which a
    # fresh clone does not keep: its collectionId is then the invocation, not the contract.
    if len(sys.argv) == 4 and sys.argv[3] == '--volatile-collection':
        DROP_KEYS.add('collectionId')
    with open(src, encoding='utf-8') as fh:
        text = fh.read()
    if src.endswith('.html'):
        out = canonical_server(text)
        if out is None:
            return 0   # no server series: nothing written, and the caller compares the absence
    else:
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
