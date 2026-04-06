#!/usr/bin/env python3
"""Generates a markdown benchmark report from koci and oras-go JSON results."""

import json
import os
import platform
import subprocess
import sys
from collections import defaultdict
from datetime import datetime
from statistics import mean


def percentile(data, p):
    sorted_data = sorted(data)
    k = (len(sorted_data) - 1) * (p / 100.0)
    f = int(k)
    c = f + 1
    if c >= len(sorted_data):
        return sorted_data[f]
    return sorted_data[f] + (k - f) * (sorted_data[c] - sorted_data[f])


def load_results(path):
    with open(path) as f:
        return json.load(f)


def group_results(results):
    groups = defaultdict(list)
    cold_start = None
    for r in results:
        if r.get("coldStart"):
            cold_start = r
            continue
        if r.get("warmup"):
            continue
        key = (r["operation"], r["sizeLabel"])
        groups[key].append(r["durationMs"])
    return groups, cold_start


def get_version(cmd):
    try:
        return subprocess.check_output(cmd, shell=True, stderr=subprocess.STDOUT).decode().strip()
    except Exception:
        return "unknown"


def fmt_ms(ms):
    if ms >= 1000:
        return f"{ms/1000:.2f}s"
    return f"{ms:.1f}ms"


def fmt_ratio(r):
    if r >= 1.05:
        return f"{r:.1f}x slower"
    elif r <= 0.95:
        return f"{1/r:.1f}x faster"
    else:
        return "~same"


def pad(text, width):
    return text.ljust(width)


def table_row(cells, widths):
    parts = [f" {pad(cell, w)} " for cell, w in zip(cells, widths)]
    return "|" + "|".join(parts) + "|"


def table_sep(widths):
    return "|" + "|".join("-" * (w + 2) for w in widths) + "|"


def find_labels(oras_groups, koci_groups, op):
    """Find all sizeLabels for an operation that exist in BOTH result sets."""
    oras_labels = {k[1] for k in oras_groups if k[0] == op}
    koci_labels = {k[1] for k in koci_groups if k[0] == op}
    return sorted(oras_labels & koci_labels)


def emit_op(lines, widths, headers, op, oras_groups, koci_groups, summary_ratios, label_override=None):
    """Emit rows for an operation, auto-discovering labels from data."""
    labels = find_labels(oras_groups, koci_groups, op)
    if not labels:
        return False

    lines.append(table_row(headers, widths))
    lines.append(table_sep(widths))

    for label in labels:
        key = (op, label)
        oras_times = oras_groups[key]
        koci_times = koci_groups[key]

        om, op95 = mean(oras_times), percentile(oras_times, 95)
        km, kp95 = mean(koci_times), percentile(koci_times, 95)
        r = km / om if om > 0 else 0
        summary_ratios[op].append(r)

        display = label_override or label
        lines.append(table_row([display, fmt_ms(om), fmt_ms(op95), fmt_ms(km), fmt_ms(kp95), fmt_ratio(r)], widths))

    return True


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    results_dir = os.path.join(script_dir, "results")

    koci_path = os.path.join(results_dir, "koci.json")
    oras_path = os.path.join(results_dir, "oras-go.json")

    if not os.path.exists(koci_path) or not os.path.exists(oras_path):
        print(f"Error: missing result files in {results_dir}", file=sys.stderr)
        sys.exit(1)

    koci_data = load_results(koci_path)
    oras_data = load_results(oras_path)

    koci_groups, koci_cold = group_results(koci_data)
    oras_groups, oras_cold = group_results(oras_data)

    go_version = get_version("go version")
    java_version = get_version("java -version 2>&1 | head -1")
    machine = platform.platform()
    date = datetime.now().strftime("%Y-%m-%d %H:%M")

    summary_ratios = defaultdict(list)
    tw = [14, 14, 14, 14, 14, 14]
    headers = ["Size", "oras-go mean", "oras-go p95", "koci mean", "koci p95", "How much slower?"]

    lines = []
    lines.append("# OCI Client Benchmark: koci (Kotlin) vs oras-go (Go)")
    lines.append("")
    lines.append(f"**Date**: {date}  ")
    lines.append(f"**Machine**: {machine}  ")
    lines.append(f"**JVM**: {java_version}  ")
    lines.append(f"**Go**: {go_version}  ")
    lines.append("")

    # ── Cold start ──
    cw = [14, 14, 14, 16]
    lines.append("## Cold Start")
    lines.append("")
    lines.append(table_row(["Metric", "oras-go (ms)", "koci (ms)", "How much slower?"], cw))
    lines.append(table_sep(cw))
    oras_cold_ms = oras_cold["durationMs"] if oras_cold else 0
    koci_cold_ms = koci_cold["durationMs"] if koci_cold else 0
    ratio = koci_cold_ms / oras_cold_ms if oras_cold_ms > 0 else 0
    lines.append(table_row(["First ping", f"{oras_cold_ms:.1f}", f"{koci_cold_ms:.1f}", fmt_ratio(ratio)], cw))
    lines.append("")

    # ── Per-request overhead ──
    lines.append("## Per-Request Overhead")
    lines.append("")
    lines.append("These operations transfer minimal data — the difference is pure client overhead per HTTP round-trip.")
    lines.append("")
    # Collect all size-independent ops that exist
    overhead_ops = []
    for op, display in [("ping", "Ping"), ("catalog", "Catalog"), ("resolve", "Resolve"), ("tags", "Tags")]:
        labels = find_labels(oras_groups, koci_groups, op)
        for label in labels:
            overhead_ops.append((op, label, display))
    if overhead_ops:
        lines.append(table_row(headers, tw))
        lines.append(table_sep(tw))
        for op, label, display in overhead_ops:
            oras_times = oras_groups[(op, label)]
            koci_times = koci_groups[(op, label)]
            om, op95 = mean(oras_times), percentile(oras_times, 95)
            km, kp95 = mean(koci_times), percentile(koci_times, 95)
            r = km / om if om > 0 else 0
            summary_ratios[op].append(r)
            lines.append(table_row([display, fmt_ms(om), fmt_ms(op95), fmt_ms(km), fmt_ms(kp95), fmt_ratio(r)], tw))
    lines.append("")

    # ── Pull ──
    pull_labels = find_labels(oras_groups, koci_groups, "pull")
    if pull_labels:
        lines.append("## Pull")
        lines.append("")
        lines.append(table_row(headers, tw))
        lines.append(table_sep(tw))
        for label in pull_labels:
            oras_times = oras_groups[("pull", label)]
            koci_times = koci_groups[("pull", label)]
            om, op95 = mean(oras_times), percentile(oras_times, 95)
            km, kp95 = mean(koci_times), percentile(koci_times, 95)
            r = km / om if om > 0 else 0
            summary_ratios["pull"].append(r)
            lines.append(table_row([label, fmt_ms(om), fmt_ms(op95), fmt_ms(km), fmt_ms(kp95), fmt_ratio(r)], tw))
        lines.append("")

    # ── Push ──
    push_labels = find_labels(oras_groups, koci_groups, "push")
    if push_labels:
        lines.append("## Push")
        lines.append("")
        lines.append("Single-shot measurement per size — registries deduplicate blobs by digest, so repeated  ")
        lines.append("pushes of the same content would measure dedup speed, not upload speed. Each client  ")
        lines.append("pushes a unique blob to a fresh repo.")
        lines.append("")
        lines.append(table_row(headers, tw))
        lines.append(table_sep(tw))
        for label in push_labels:
            oras_times = oras_groups[("push", label)]
            koci_times = koci_groups[("push", label)]
            om, op95 = mean(oras_times), percentile(oras_times, 95)
            km, kp95 = mean(koci_times), percentile(koci_times, 95)
            r = km / om if om > 0 else 0
            summary_ratios["push"].append(r)
            lines.append(table_row([label, fmt_ms(om), fmt_ms(op95), fmt_ms(km), fmt_ms(kp95), fmt_ratio(r)], tw))
        lines.append("")

    # ── Multi-tag ──
    mt_list_labels = find_labels(oras_groups, koci_groups, "tags-many-list")
    mt_proc_labels = find_labels(oras_groups, koci_groups, "tags-many-process")
    if mt_list_labels or mt_proc_labels:
        tag_count = mt_list_labels[0] if mt_list_labels else mt_proc_labels[0]
        lines.append(f"## Multi-Tag ({tag_count})")
        lines.append("")
        lines.append("The bottleneck isn't listing tags — it's resolving the manifest for each one.")
        lines.append("")
        cw_mt = [26, 14, 14, 14, 14, 14]
        mt_headers = ["Operation"] + headers[1:]
        mt_rows = []
        if mt_list_labels:
            mt_rows.append(("tags-many-list", mt_list_labels[0], "List tags only"))
        if mt_proc_labels:
            mt_rows.append(("tags-many-process", mt_proc_labels[0], "List + resolve + manifest"))

        lines.append(table_row(mt_headers, cw_mt))
        lines.append(table_sep(cw_mt))
        for op, label, display in mt_rows:
            oras_times = oras_groups[(op, label)]
            koci_times = koci_groups[(op, label)]
            om, op95 = mean(oras_times), percentile(oras_times, 95)
            km, kp95 = mean(koci_times), percentile(koci_times, 95)
            r = km / om if om > 0 else 0
            summary_ratios[op].append(r)
            lines.append(table_row([display, fmt_ms(om), fmt_ms(op95), fmt_ms(km), fmt_ms(kp95), fmt_ratio(r)], cw_mt))
        lines.append("")

    # ── Parallel pull ──
    par_seq_labels = find_labels(oras_groups, koci_groups, "parallel-seq")
    par_conc_labels = find_labels(oras_groups, koci_groups, "parallel-conc")
    if par_seq_labels or par_conc_labels:
        pkg_count = par_seq_labels[0] if par_seq_labels else par_conc_labels[0]
        lines.append(f"## Parallel Pull ({pkg_count})")
        lines.append("")
        cw_par = [18, 14, 14, 14, 14, 14]
        par_headers = ["Mode"] + headers[1:]
        lines.append(table_row(par_headers, cw_par))
        lines.append(table_sep(cw_par))

        for op, op_labels, display in [
            ("parallel-seq", par_seq_labels, "Sequential"),
            ("parallel-conc", par_conc_labels, "Concurrent"),
        ]:
            if not op_labels:
                continue
            label = op_labels[0]
            oras_times = oras_groups[(op, label)]
            koci_times = koci_groups[(op, label)]
            om, op95 = mean(oras_times), percentile(oras_times, 95)
            km, kp95 = mean(koci_times), percentile(koci_times, 95)
            r = km / om if om > 0 else 0
            summary_ratios[op].append(r)
            lines.append(table_row([display, fmt_ms(om), fmt_ms(op95), fmt_ms(km), fmt_ms(kp95), fmt_ratio(r)], cw_par))

        # Speedup
        if par_seq_labels and par_conc_labels:
            oras_seq = oras_groups.get(("parallel-seq", par_seq_labels[0]), [])
            oras_conc = oras_groups.get(("parallel-conc", par_conc_labels[0]), [])
            koci_seq = koci_groups.get(("parallel-seq", par_seq_labels[0]), [])
            koci_conc = koci_groups.get(("parallel-conc", par_conc_labels[0]), [])
            if oras_seq and oras_conc and koci_seq and koci_conc:
                oras_speedup = mean(oras_seq) / mean(oras_conc) if mean(oras_conc) > 0 else 0
                koci_speedup = mean(koci_seq) / mean(koci_conc) if mean(koci_conc) > 0 else 0
                lines.append("")
                lines.append(f"> **Concurrency speedup:** oras-go: **{oras_speedup:.1f}x**, koci: **{koci_speedup:.1f}x**")
        lines.append("")

    # ── Compound flow ──
    comp_labels = find_labels(oras_groups, koci_groups, "compound-list") or find_labels(oras_groups, koci_groups, "compound-full")
    if comp_labels:
        lines.append("## Real-World Discovery Flow (UDS Android `retrieveMetadata`)")
        lines.append("")
        lines.append("Simulates the actual UDS Android package discovery:")
        lines.append("")
        lines.append("1. **`registry.extensions.list()`** — catalog + sequential `tags()` per repo")
        lines.append("2. **For each repo+tag** — resolve → manifest")
        lines.append("")
        lines.append("The `list()` call serializes ALL tag fetches via `flatMapConcat`.  ")
        lines.append("Per-request overhead compounds across every repository in the registry.")
        lines.append("")
        cw_comp = [24, 14, 14, 14, 14, 14]
        comp_headers = ["Flow"] + headers[1:]
        comp_rows = []
        for op, display in [("compound-list", "list() only"), ("compound-full", "list() + process all")]:
            labels = find_labels(oras_groups, koci_groups, op)
            if labels:
                comp_rows.append((op, labels[0], display))

        if comp_rows:
            lines.append(table_row(comp_headers, cw_comp))
            lines.append(table_sep(cw_comp))
            for op, label, display in comp_rows:
                oras_times = oras_groups[(op, label)]
                koci_times = koci_groups[(op, label)]
                om, op95 = mean(oras_times), percentile(oras_times, 95)
                km, kp95 = mean(koci_times), percentile(koci_times, 95)
                r = km / om if om > 0 else 0
                summary_ratios[op].append(r)
                lines.append(table_row([display, fmt_ms(om), fmt_ms(op95), fmt_ms(km), fmt_ms(kp95), fmt_ratio(r)], cw_comp))
        lines.append("")
        lines.append("> With N repos, `list()` makes 1 + N HTTP requests. The full flow adds up to 4 more per repo.")
        lines.append("")

    # ── Summary ──
    lines.append("## Summary")
    lines.append("")
    sw = [28, 26]
    lines.append(table_row(["Operation", "koci vs oras-go (avg)"], sw))
    lines.append(table_sep(sw))

    all_ops = [
        ("ping",              "Ping"),
        ("catalog",           "Catalog"),
        ("resolve",           "Resolve"),
        ("tags",              "Tags"),
        ("pull",              "Pull"),
        ("push",              "Push"),
        ("tags-many-list",    "List tags (multi-tag)"),
        ("tags-many-process", "Resolve each (multi-tag)"),
        ("parallel-seq",      "Parallel pull (sequential)"),
        ("parallel-conc",     "Parallel pull (concurrent)"),
        ("compound-list",     "Discovery: list()"),
        ("compound-full",     "Discovery: full flow"),
    ]

    for op, display in all_ops:
        ratios = summary_ratios.get(op, [])
        if ratios:
            lines.append(table_row([display, fmt_ratio(mean(ratios))], sw))
    lines.append("")

    # ── Methodology ──
    lines.append("## Methodology")
    lines.append("")
    lines.append("- Both clients tested against the same registry, run sequentially (no concurrent load)")
    lines.append("- Warm-up iterations discarded to account for JVM JIT and connection pool warm-up")
    lines.append("- Each pull uses a fresh temp directory; each push uses a unique tag")
    lines.append("- Cold start measured separately (first operation after process start)")
    lines.append("- \"Xx slower\" means koci took X times longer than oras-go for the same operation")
    lines.append("- Compound flow mirrors `RegistryPackageServiceImpl.retrieveMetadata()` in UDS Android")
    lines.append("- Zarf packages are standard OCI artifacts — all tests apply directly to Zarf workflows")
    lines.append("")

    report = "\n".join(lines)

    output_path = os.path.join(results_dir, "report.md")
    with open(output_path, "w") as f:
        f.write(report)

    print(report)
    print(f"\nReport written to {output_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
