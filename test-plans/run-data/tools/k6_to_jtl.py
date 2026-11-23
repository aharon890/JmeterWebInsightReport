"""Reference converter for the spec 001 fixtures: k6's CSV -> JMeter 5.6.3 CSV JTL.

Origin: perf hub's research for spec 010 (tmp/k6-web-insight-report/shapes/k6_to_jtl_v3.py, 2026-11-23). It matched
JMeter's Aggregate Report in 198 of 198 referee checks. Its one use here is to write the JMeter-format copies of the
committed k6 fixtures (results.jtl, in-test-plan.jtl), so that R07 holds them. It is an independent translation that the
report's own k6 adapter (core/adapter/k6) must agree with. It is never part of the product.

Proof of concept v3 (spec 010 plan): k6 CSV -> JMeter 5.6.3 CSV JTL that Web Insight Report nests like a JMeter run.

Rules (WIR TransactionRows, JMeter TransactionSampler, run #63):
- JMeter's default 17-column header.
- A complete group pass (vu, iter, group path, group_duration) = a transaction row written FIRST, on the pass's thread:
  start = min(pass start, first child's start); elapsed = sum of its direct children's elapsed; IdleTime = pass end -
  start - elapsed; message "Number of samples in transaction : N, number of failing samples : M" with N = direct
  children (a nested pass counts as one); responseCode 200 or the first failing child's; Connect = sum of children's.
  Then its children, by start, depth-first, on the same thread.
- A pass cut off by the end of the test (requests, no group_duration) = the same shape without the message, only when
  the group's name completed somewhere in the file; otherwise its requests are plain rows.
- Requests outside groups, setup and teardown: plain rows. Files without vu/iter: plain rows only.
usage: python -I k6_to_jtl_v3.py results.csv.gz out.jtl
"""
import csv, gzip, sys
from collections import defaultdict

src, dst = sys.argv[1], sys.argv[2]
opener = gzip.open if src.endswith(".gz") else open
HEADER = ["timeStamp", "elapsed", "label", "responseCode", "responseMessage", "threadName", "dataType", "success",
          "failureMessage", "bytes", "sentBytes", "grpThreads", "allThreads", "URL", "Latency", "IdleTime", "Connect"]


def ms(ts):
    n = int(ts)
    return n * 1000 if n < 10**11 else n


def meta(md):
    d = dict(p.split("=", 1) for p in md.split("&") if "=" in p)
    return d.get("vu"), d.get("iter")


reqs, passes, vus, cur = [], [], [], None


def close(c):
    r0 = c["r"]
    vu, it = meta(r0.get("metadata", ""))
    el = round(c.get("http_req_duration", 0.0))
    exp = r0.get("expected_response", "")
    ok = exp == "true" if exp else (c.get("http_req_failed", 0.0) == 0.0 if "http_req_failed" in c else
                                     (r0["status"].isdigit() and 200 <= int(r0["status"]) < 400 and not r0["error"]))
    reqs.append({"kind": "req", "r": r0, "vu": vu, "iter": it, "scenario": r0["scenario"], "path": r0["group"],
                 "end": c["end"], "start": c["end"] - el, "elapsed": el, "ok": ok,
                 "latency": round(c.get("http_req_sending", 0) + c.get("http_req_waiting", 0)),
                 "connect": round(c.get("http_req_connecting", 0) + c.get("http_req_tls_handshaking", 0))})


with opener(src, "rt", newline="") as f:
    for r in csv.DictReader(f):
        m = r["metric_name"]
        if m == "http_reqs":  # a request starts here; it ends at the next http_reqs or any other metric
            if cur: close(cur)
            cur = {"end": ms(r["timestamp"]), "r": r}
            continue
        if m.startswith("http_req_") and cur is not None:
            cur[m] = float(r["metric_value"]); continue
        if cur: close(cur); cur = None
        if m == "vus":
            vus.append((ms(r["timestamp"]), int(float(r["metric_value"]))))
        elif m == "group_duration":
            vu, it = meta(r.get("metadata", ""))
            end, dur = ms(r["timestamp"]), round(float(r["metric_value"]))
            passes.append({"kind": "tx", "path": r["group"], "vu": vu, "iter": it, "scenario": r["scenario"],
                           "end": end, "start": end - dur, "kids": [], "cut": False})
    if cur: close(cur)

has_meta = any(q["vu"] for q in reqs)
by_key = defaultdict(list)
for p in passes:
    by_key[(p["vu"], p["iter"])].append(p)


def find_pass(vu, it, path, start, end):
    c = [p for p in by_key[(vu, it)] if p["path"] == path and p["start"] - 1 <= start and end <= p["end"] + 1]
    return min(c, key=lambda p: p["end"] - p["start"]) if c else None


completed_names = {p["path"].rsplit("::", 1)[-1] for p in passes}
top = []


def attach(x, path):
    """Put x (request or pass) into the pass for `path` in its vu/iter; cut-off passes are made on demand."""
    if not path or path in ("::setup", "::teardown") or not x["vu"]:
        top.append(x); return
    p = find_pass(x["vu"], x["iter"], path, x["start"], x["end"])
    if p is None:
        p = next((c for c in by_key[(x["vu"], x["iter"])] if c["cut"] and c["path"] == path), None)
        if p is None:
            if path.rsplit("::", 1)[-1] not in completed_names:  # never completed anywhere: plain rows
                top.append(x); return
            p = {"kind": "tx", "path": path, "vu": x["vu"], "iter": x["iter"], "scenario": x["scenario"],
                 "kids": [], "cut": True, "start": x["start"], "end": x["end"]}
            by_key[(x["vu"], x["iter"])].append(p)
            attach(p, path.rsplit("::", 1)[0])  # the cut-off pass itself goes into its parent
        p["start"] = min(p["start"], x["start"]); p["end"] = max(p["end"], x["end"])
    p["kids"].append(x)


if has_meta:
    for p in sorted(passes, key=lambda p: -p["path"].count("::")):  # inner passes find their parents first
        attach(p, p["path"].rsplit("::", 1)[0])
    for q in reqs:
        attach(q, q["path"])
else:
    top = list(reqs)


def vus_at(t):
    v = 1
    for when, n in vus:
        if when > t: break
        v = n
    return v


def thread(x):
    return f"{x['scenario']} 1-{x['vu']}" if x.get("vu") else (x["path"].strip(":") or "k6")


def finish(p):  # bottom-up: clamp start to the first child, sum elapsed, count failures
    for k in p["kids"]:
        if k["kind"] == "tx": finish(k)
    p["kids"].sort(key=lambda k: k["start"])
    p["start"] = min([p["start"]] + [k["start"] for k in p["kids"]])
    # WIR nests a row only when it starts strictly before start + elapsed + idle: end at least 1 ms after the last start
    p["end"] = max([p["end"]] + [k["start"] + 1 for k in p["kids"]])
    p["elapsed"] = sum(k["elapsed"] for k in p["kids"])
    p["failed"] = [k for k in p["kids"] if not k["ok"]]
    p["ok"] = not p["failed"]
    p["code"] = "200" if p["ok"] else (p["failed"][0]["r"]["status"] if p["failed"][0]["kind"] == "req" else p["failed"][0]["code"])
    p["connect"] = sum(k["connect"] for k in p["kids"])


rows = []


def emit(x):
    n = vus_at(x["end"])
    if x["kind"] == "req":
        r = x["r"]
        code = r["status"] if r["status"] not in ("", "0") else f"Non HTTP response code: {r['error_code'] or 0}"
        rows.append([x["start"], x["elapsed"], r["name"], code, r["error"] or ("OK" if x["ok"] else ""), thread(x),
                     "text", str(x["ok"]).lower(), "", 0, 0, n, n, "", x["latency"], 0, x["connect"]])
        return
    msg = "" if x["cut"] else (f"Number of samples in transaction : {len(x['kids'])}, "
                               f"number of failing samples : {len(x['failed'])}")
    rows.append([x["start"], x["elapsed"], x["path"].rsplit("::", 1)[-1], x["code"], msg, thread(x), "",
                 str(x["ok"]).lower(), "", 0, 0, n, n, "null", 0, max(0, x["end"] - x["start"] - x["elapsed"]),
                 x["connect"]])
    for k in x["kids"]: emit(k)


for x in top:
    if x["kind"] == "tx": finish(x)
for x in sorted(top, key=lambda x: x["end"]):
    emit(x)
if len(sys.argv) > 3:
    saved = list(rows); rows.clear(); tops = []
    for x in sorted(top, key=lambda x: x["end"]):
        n0 = len(rows); emit(x); tops.append(rows[n0])
    rows.clear(); rows.extend(saved)
    with open(sys.argv[3], "w", newline="") as out2:
        w2 = csv.writer(out2, lineterminator="\n"); w2.writerow(HEADER); w2.writerows(tops)
with open(dst, "w", newline="") as out:
    w = csv.writer(out, lineterminator="\n"); w.writerow(HEADER); w.writerows(rows)
print(f"{len(reqs)} requests, {len(passes)} complete passes, "
      f"{sum(1 for x in top if x['kind'] == 'tx' and x['cut'])} cut-off top-level passes -> {dst}")
