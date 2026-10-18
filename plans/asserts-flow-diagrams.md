# Asserts — how it works, in diagrams

> Companion to [`asserts-core-hardening.md`](asserts-core-hardening.md). Every diagram shows the
> **target** design (after the core plan), except D4 and D8 which contrast today with the target.
> Mermaid renders in JetBrains markdown preview and on GitHub.

---

## D1 — The whole loop: where asserts come from, where verdicts go

```mermaid
flowchart LR
    subgraph AUTHOR["1 · AUTHOR"]
        direction TB
        GUI["Asserts tab<br/>in any report"]
        EDT["Any JSON editor<br/>+ published schema"]
    end

    FILE[("report-annotations.json<br/><b>the only assert input</b>")]

    GUI -->|"Download"| FILE
    EDT --> FILE
    FILE -.->|"<b>Open file…</b><br/>(new — no import exists today)"| GUI

    subgraph RUN["2 · RUN"]
        direction TB
        LIS["<b>Listener path</b><br/>jmeter -n -t plan.jmx<br/>or the GUI listener"]
        EXP["<b>Exporter path</b><br/>jmeter -g results.jtl -o dir"]
    end

    FILE -->|"-Jwebinsight.report.annotations<br/>· GUI field (new)<br/>· auto-detect in output dir"| LIS
    FILE --> EXP

    GE{{"<b>GateEvaluation</b><br/>the only judge"}}
    LIS --> GE
    EXP --> GE

    subgraph OUT["3 · OUTPUTS"]
        direction TB
        HTML["report.html"]
        JSON["report.json<br/><i>CI/CD summary — an OUTPUT,<br/>not the assert file</i>"]
        XML["report.xml (JUnit)"]
        STD["stdout verdict line"]
        EXIT["exit code<br/>-Jwebinsight.report.failonassert"]
    end

    GE --> HTML & JSON & XML & STD & EXIT
    HTML -.->|"opens in a browser"| GUI

    style FILE fill:#dbeafe,stroke:#1d4ed8,stroke-width:2px
    style GE fill:#dcfce7,stroke:#166534,stroke-width:2px
```

**The loop is the point.** You author, you run, you read the actual values in the report, you retune
in the tab, you download, you run again. The report is both the output and the next input's editor.

---

## D2 — One rule → one status

This is the whole of requirement 2. **Only the green box can produce a status that gates.**

```mermaid
flowchart TD
    R["A rule from<br/><b>assertions[]</b>"] --> Q1{"enabled: false?"}
    Q1 -->|yes| NJ1["NOT_JUDGED<br/><i>DISABLED</i>"]
    Q1 -->|no| Q2{"Is the rule readable?<br/>metric known · target parseable<br/>· at least one band"}
    Q2 -->|no| NJ2["NOT_JUDGED<br/><i>INVALID_RULE</i>"]
    Q2 -->|yes| Q3{"Does the target select a sampler<br/>that actually ran?"}
    Q3 -->|no| NJ3["NOT_JUDGED<br/><i>NO_MATCH</i>"]
    Q3 -->|yes| Q4{"Can this run produce the metric?<br/><i>e.g. JTL saved without Connect</i>"}
    Q4 -->|no| NJ4["NOT_JUDGED<br/><i>NO_VALUE</i>"]
    Q4 -->|yes| J["<b>judge</b><br/>Predicate.breaches(actual)"]

    J --> Q5{"fail band breached?"}
    Q5 -->|yes| F["<b>FAIL</b>"]
    Q5 -->|no| Q6{"warn band breached?"}
    Q6 -->|yes| W["<b>WARN</b>"]
    Q6 -->|no| P["<b>PASS</b>"]

    style J fill:#dcfce7,stroke:#166534,stroke-width:3px
    style F fill:#fee2e2,stroke:#991b1b
    style W fill:#fef3c7,stroke:#92400e
    style P fill:#dcfce7,stroke:#166534
    style NJ1 fill:#f1f5f9,stroke:#64748b
    style NJ2 fill:#f1f5f9,stroke:#64748b
    style NJ3 fill:#f1f5f9,stroke:#64748b
    style NJ4 fill:#f1f5f9,stroke:#64748b
```

Every grey box is **neutral in both directions** — it cannot fail the run and it cannot pass it.
`fail` is tested before `warn`, so a value past both bands is a failure, not a warning.

---

## D3 — The two folds

The same rule is applied twice: matches → rule, rules → run. Nothing else folds anywhere.

```mermaid
flowchart LR
    subgraph MATCHES["Rule A · target: match '*'"]
        direction TB
        M1["Login &nbsp;812 ms → FAIL"]
        M2["Search &nbsp;240 ms → PASS"]
        M3["Cart &nbsp;&nbsp;610 ms → WARN"]
        M4["Health &nbsp;— → NO_VALUE"]
    end

    MATCHES --> F1{{"worst of the<br/><b>measured</b> matches"}}
    F1 --> RA["Rule A = <b>FAIL</b>"]

    RB["Rule B = PASS"]
    RC["Rule C = NOT_JUDGED"]

    RA --> F2
    RB --> F2
    RC --> F2

    F2{{"any FAIL → FAIL<br/>else any WARN → WARN<br/>else any PASS → PASS<br/>else NOT EVALUATED"}}
    F2 --> RUN["Run verdict = <b>FAIL</b>"]

    style F1 fill:#dbeafe,stroke:#1d4ed8
    style F2 fill:#dbeafe,stroke:#1d4ed8
    style RUN fill:#fee2e2,stroke:#991b1b,stroke-width:2px
```

Note `Health` in the fold. **Today one unmeasurable match poisons the entire rule to INVALID,
which erases the genuine FAIL from `Login`.** After: the rule reports the worst of what it could
measure, and the unmeasurable match is reported separately.

And note the last branch: a gate of nothing but NOT_JUDGED rules is **NOT EVALUATED, not PASS.**
A run that judged nothing has not passed anything.

---

## D4 — What actually changes: the paths to FAIL

```mermaid
flowchart TB
    subgraph T["TODAY — five ways in, four of them yours by accident"]
        direction LR
        T1["a fail band breached"] --> TF(("FAIL"))
        T2["typo'd metric<br/><i>→ INVALID</i>"] --> TF
        T3["JTL missing a column<br/><i>→ NaN → INVALID</i>"] --> TF
        T4["sampler renamed<br/><i>→ onMissing: fail</i>"] --> TF
        T5["the badge fold turns<br/>INVALID → FAIL"] --> TF
    end

    subgraph A["AFTER — one way in"]
        direction LR
        A1["a fail band breached"] --> AF(("FAIL"))
        A2["typo'd metric"] --> AN(("NOT_JUDGED"))
        A3["JTL missing a column"] --> AN
        A4["sampler renamed"] --> AN
        AN --> BAN["🔴 blocking banner<br/>+ ERROR finding<br/>+ opt-in CI counter"]
    end

    style TF fill:#fee2e2,stroke:#991b1b,stroke-width:3px
    style AF fill:#fee2e2,stroke:#991b1b,stroke-width:3px
    style AN fill:#f1f5f9,stroke:#64748b,stroke-width:2px
    style BAN fill:#fef2f2,stroke:#991b1b
```

**This is the trade as it shipped — and this diagram's AFTER column is about `gate.status`
only.** A typo stops being a *verdict*: no more FAIL with no number behind it. It does **not**
stop failing the build. A rule that could not be built is a **configuration error**, so since
**3.1.0** the run exits non-zero under `failonerror` (default **true**), prints
`WEB-INSIGHT-REPORT: CONFIG ERROR` on stderr, and still writes every artifact — failing a build
must not cost the report that explains why. A gate that genuinely **FAILED** exits 0; that is a
working gate returning an answer, and `failonassert` is how you ask for it to be fatal.
Reversed 2026-08-08, planned and executed in
[`asserts-config-errors-fail.md`](asserts-config-errors-fail.md).

What is still a `jq` you choose to write is a rule that merely *matched nothing* — that one can
legitimately be a plan running a subset:

```bash
jq -e '.gate.failed == 0 and .gate.notJudged.noMatch == 0' report.json
```

---

## D5 — CRUD in the browser: what state exists and how it moves

```mermaid
stateDiagram-v2
    direction LR
    [*] --> Loaded : report opens<br/>payload carries the document

    Loaded --> Dirty : create · update · delete<br/><i>any block, any rule</i>
    Dirty --> Dirty : more edits<br/><i>preview re-evaluates</i>
    Dirty --> Loaded : Discard
    Dirty --> Saved : Download report-annotations.json

    Loaded --> Loaded : Open file…<br/><i>replaces the document</i>
    Dirty --> Conflict : Open file…<br/><i>drafts pending</i>
    Conflict --> Loaded : Discard my drafts,<br/>load the file
    Conflict --> Dirty : Cancel

    Saved --> [*] : the next run reads it

    note right of Dirty
        ONE candidate document,
        diffed against the loaded one.
        Replaces {rules, deletions},
        which cannot represent
        "a block was cleared".
    end note
```

The current draft model can only describe rule edits. That is *why* no other block is editable —
not an oversight, a missing data structure.

---

## D6 — One evaluation, six surfaces — and the one that is only a preview

```mermaid
flowchart TD
    JAVA["<b>GateEvaluation</b> · Java · at generation time"] --> BAKED[["state.gate<br/><b>baked — never recomputed</b>"]]

    BAKED --> S1["Header badge"]
    BAKED --> S2["Summary gate strip"]
    BAKED --> S3["Asserts tab rows"]
    BAKED --> S4["report.json"]
    BAKED --> S5["report.xml"]
    BAKED --> S6["exit code"]

    EDIT["You edit a threshold<br/>in the Asserts tab"] --> MIR["client mirror<br/><i>same algorithm, in JS</i>"]
    MIR --> DRAFT[["state.draftGate<br/><b>preview only</b>"]]
    DRAFT --> S3
    DRAFT -.->|"<b>never</b>"| S4
    DRAFT -.->|"<b>never</b>"| S6

    style BAKED fill:#dcfce7,stroke:#166534,stroke-width:2px
    style DRAFT fill:#fef3c7,stroke:#92400e,stroke-width:2px
```

**The invariant that must never break:** what the artifacts say is what Java decided. Editing in the
browser shows you what *would* happen; it cannot change what *did*. The tab says *re-run to make
this real*.

The two implementations are kept honest by `assert-parity-vectors.json`, run in Java by
`AssertionParityTest` and in the browser by `asserts-parity.spec.ts`. **That vector file is the
only regression brake this feature has.**

---

## D7 — A headless run, end to end

```mermaid
sequenceDiagram
    autonumber
    participant CI as CI job
    participant JM as JMeter
    participant PL as Plugin
    participant AL as AnnotationLoader
    participant EV as GateEvaluation

    CI->>JM: jmeter -n -t plan.jmx<br/>-Jwebinsight.report.annotations=asserts.json<br/>-Jwebinsight.report.failonassert=true
    JM->>PL: testStarted
    PL->>AL: resolve + load asserts.json

    alt named file missing or unparseable
        AL --x CI: <b>fail the run</b> — new<br/><i>today it warns, then writes a green gateless report</i>
    else loaded
        AL -->> PL: rules
    end

    loop every sample
        JM->>PL: sampleOccurred
    end

    JM->>PL: testEnded
    PL->>EV: evaluate(rules, data)
    EV-->>PL: GateSummary
    PL ->> CI: stdout — "Asserts: FAIL — 12 declared, 6 passed, 2 failed"
    PL ->> CI: report.html · report.json · report.xml
    PL --x CI: exit 1
```

Step 3 is the one gap that matters: a typo in the path is a **green** run today.

---

## D8 — Where the retired threshold block went

```mermaid
flowchart LR
    subgraph N["Today — a special case in five places"]
        direction TB
        N1["typed field + 3 accessors<br/>ReportAnnotations"]
        N2["bespoke ERROR finding<br/>GateEvaluation"]
        N3["written back by documentOf<br/>GatePayloadBuilder"]
        N4["unused Thymeleaf variable<br/>ThymeleafReportRenderer"]
        N5["deliberately dropped on save<br/>report.js + the known map"]
    end

    N ==>|"delete all of it"| G

    subgraph G["After — no special case at all"]
        direction TB
        G1["an unrecognised top-level key"]
        G2["preserved verbatim on save<br/><i>like _comment</i>"]
        G3["no finding, no special case<br/>— we do not know the key"]
    end

    style N fill:#fef2f2,stroke:#991b1b
    style G fill:#f0fdf4,stroke:#166534
```

The block stops being a feature the code knows about. A file carrying one is **exactly as
interesting as a file carrying any other key we do not recognise** — preserved, named once in a
warning, never evaluated.

---

## The one-paragraph version

A tester writes `report-annotations.json` — by hand or from the Asserts tab. A run reads it,
and **`GateEvaluation` judges it once**. Each rule gets one of four statuses, and only a breached
`fail` band can produce FAIL. The worst measured match decides the rule; the worst rule decides the
run; anything that could not be judged is neutral and shouts through a banner instead. That single
verdict is baked into the HTML, the CI JSON, the JUnit XML, stdout and the exit code. Opening the
HTML lets you retune against the real numbers, preview the effect, and download the file for the
next run.
