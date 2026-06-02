#!/usr/bin/env python3
"""Generates a markdown benchmark report from koci v1, koci current, and oras-go JSON results."""

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
    sizes = {}
    for r in results:
        key = (r["operation"], r["sizeLabel"])
        groups[key].append(r["durationMs"])
        if r.get("sizeBytes"):
            sizes[key] = r["sizeBytes"]
    return groups, sizes


def get_version(cmd):
    try:
        return subprocess.check_output(cmd, shell=True, stderr=subprocess.STDOUT).decode().strip()
    except Exception:
        return "unknown"


def fmt_ms(ms):
    if ms >= 1000:
        return f"{ms/1000:.2f}s"
    return f"{ms:.1f}ms"


def fmt_size(b):
    if b >= 1024 * 1024 * 1024:
        return f"{b / (1024**3):.1f}GB"
    if b >= 1024 * 1024:
        return f"{b / (1024**2):.0f}MB"
    if b >= 1024:
        return f"{b / 1024:.0f}KB"
    return f"{b}B"


def fmt_ratio(r):
    if r == 0:
        return "n/a"
    if r >= 1.05:
        return f"{r:.1f}x slower"
    elif r <= 0.95:
        return f"{1/r:.1f}x faster"
    else:
        return "~same"



def pad(text, width):
    return text.ljust(width)


def table_row(cells, widths):
    parts = [f" {pad(str(cell), w)} " for cell, w in zip(cells, widths)]
    return "|" + "|".join(parts) + "|"


def table_sep(widths):
    return "|" + "|".join("-" * (w + 2) for w in widths) + "|"


def find_labels(groups_a, groups_b, op):
    labels_a = {k[1] for k in groups_a if k[0] == op}
    labels_b = {k[1] for k in groups_b if k[0] == op}
    return sorted(labels_a & labels_b)


def stats(times):
    return mean(times), percentile(times, 95)


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    results_dir = os.path.join(script_dir, "results")

    v1_path = os.path.join(results_dir, "koci-v1.json")
    current_path = os.path.join(results_dir, "koci-current.json")
    oras_path = os.path.join(results_dir, "oras-go.json")

    has_v1 = os.path.exists(v1_path)
    has_current = os.path.exists(current_path)
    has_oras = os.path.exists(oras_path)

    if not has_v1 and not has_current and not has_oras:
        print(f"Error: no result files found in {results_dir}", file=sys.stderr)
        sys.exit(1)

    v1_data = load_results(v1_path) if has_v1 else []
    current_data = load_results(current_path) if has_current else []
    oras_data = load_results(oras_path) if has_oras else []

    v1_groups, v1_sizes = group_results(v1_data)
    current_groups, current_sizes = group_results(current_data)
    oras_groups, oras_sizes = group_results(oras_data)

    all_groups = [g for g, has in [(v1_groups, has_v1), (current_groups, has_current), (oras_groups, has_oras)] if has]
    iterations = max((len(v) for g in all_groups for v in g.values()), default=0)

    go_version = get_version("go version")
    java_version = get_version("java -version 2>&1 | head -1")
    machine = platform.platform()
    date = datetime.now().strftime("%Y-%m-%d %H:%M")

    lines = []
    lines.append("# OCI Client Benchmark: koci v1 vs koci current vs oras-go")
    lines.append("")
    lines.append(f"**Date**: {date}  ")
    lines.append(f"**Machine**: {machine}  ")
    lines.append(f"**JVM**: {java_version}  ")
    lines.append(f"**Go**: {go_version}  ")
    lines.append(f"**Iterations**: {iterations}  ")
    lines.append("")

    # ── Per-request overhead ──
    lines.append("## Per-Request Overhead")
    lines.append("")

    v1_summary = defaultdict(list)
    current_summary = defaultdict(list)
    cv1_summary = defaultdict(list)

    ow = [14, 12, 12, 14, 14, 20, 16]
    oh = ["Operation", "oras-go mean", "v1 mean", "current mean", "v1 vs oras", "current vs oras", "current vs v1"]
    lines.append(table_row(oh, ow))
    lines.append(table_sep(ow))

    overhead_ops = [("ping", "Ping"), ("catalog", "Catalog"), ("resolve", "Resolve"), ("tags", "Tags")]
    for op, display in overhead_ops:
        all_op_labels = set()
        if has_oras: all_op_labels |= {k[1] for k in oras_groups if k[0] == op}
        if has_v1: all_op_labels |= {k[1] for k in v1_groups if k[0] == op}
        if has_current: all_op_labels |= {k[1] for k in current_groups if k[0] == op}
        for label in sorted(all_op_labels):
            key = (op, label)
            oras_times = oras_groups.get(key, [])
            v1_times = v1_groups.get(key, [])
            curr_times = current_groups.get(key, [])
            if not oras_times and not v1_times and not curr_times:
                continue
            oras_mean = mean(oras_times) if oras_times else 0
            v1_mean = mean(v1_times) if v1_times else 0
            curr_mean = mean(curr_times) if curr_times else 0
            v1_r = v1_mean / oras_mean if oras_mean > 0 else 0
            curr_r = curr_mean / oras_mean if oras_mean > 0 else 0
            cv1_r = curr_mean / v1_mean if v1_mean > 0 else 0
            if v1_r: v1_summary[op].append(v1_r)
            if curr_r: current_summary[op].append(curr_r)
            if cv1_r: cv1_summary[op].append(cv1_r)
            lines.append(table_row([
                display,
                fmt_ms(oras_mean) if oras_times else "n/a",
                fmt_ms(v1_mean) if v1_times else "n/a",
                fmt_ms(curr_mean) if curr_times else "n/a",
                fmt_ratio(v1_r) if v1_r else "n/a",
                fmt_ratio(curr_r) if curr_r else "n/a",
                fmt_ratio(cv1_r) if cv1_r else "n/a",
            ], ow))
    lines.append("")

    # ── Pull ──
    pull_labels = set()
    if has_oras: pull_labels |= {k[1] for k in oras_groups if k[0] == "pull"}
    if has_v1: pull_labels |= {k[1] for k in v1_groups if k[0] == "pull"}
    if has_current: pull_labels |= {k[1] for k in current_groups if k[0] == "pull"}

    if pull_labels:
        lines.append("## Pull")
        lines.append("")
        pw = [22, 12, 12, 14, 14, 20, 16]
        ph = ["Size", "oras-go mean", "v1 mean", "current mean", "v1 vs oras", "current vs oras", "current vs v1"]
        lines.append(table_row(ph, pw))
        lines.append(table_sep(pw))
        for label in sorted(pull_labels):
            key = ("pull", label)
            oras_times = oras_groups.get(key, [])
            v1_times = v1_groups.get(key, [])
            curr_times = current_groups.get(key, [])
            all_sizes = {**v1_sizes, **current_sizes, **oras_sizes}
            size_bytes = all_sizes.get(key, 0)
            display = fmt_size(size_bytes) if size_bytes else label
            oras_mean = mean(oras_times) if oras_times else 0
            v1_mean = mean(v1_times) if v1_times else 0
            curr_mean = mean(curr_times) if curr_times else 0
            v1_r = v1_mean / oras_mean if oras_mean > 0 else 0
            curr_r = curr_mean / oras_mean if oras_mean > 0 else 0
            cv1_r = curr_mean / v1_mean if v1_mean > 0 else 0
            if v1_r: v1_summary["pull"].append(v1_r)
            if curr_r: current_summary["pull"].append(curr_r)
            if cv1_r: cv1_summary["pull"].append(cv1_r)
            lines.append(table_row([
                display,
                fmt_ms(oras_mean) if oras_times else "n/a",
                fmt_ms(v1_mean) if v1_times else "n/a",
                fmt_ms(curr_mean) if curr_times else "n/a",
                fmt_ratio(v1_r) if v1_r else "n/a",
                fmt_ratio(curr_r) if curr_r else "n/a",
                fmt_ratio(cv1_r) if cv1_r else "n/a",
            ], pw))
        lines.append("")

    # ── Push ──
    push_labels = set()
    if has_oras: push_labels |= {k[1] for k in oras_groups if k[0] == "push"}
    if has_v1: push_labels |= {k[1] for k in v1_groups if k[0] == "push"}
    if has_current: push_labels |= {k[1] for k in current_groups if k[0] == "push"}

    if push_labels:
        lines.append("## Push")
        lines.append("")
        all_sizes_push = {**v1_sizes, **current_sizes, **oras_sizes}
        pw2 = [22, 12, 12, 14, 14, 20, 16]
        ph2 = ["Size", "oras-go mean", "v1 mean", "current mean", "v1 vs oras", "current vs oras", "current vs v1"]
        lines.append(table_row(ph2, pw2))
        lines.append(table_sep(pw2))
        for label in sorted(push_labels, key=lambda l: all_sizes_push.get(("push", l), 0)):
            key = ("push", label)
            oras_times = oras_groups.get(key, [])
            v1_times = v1_groups.get(key, [])
            curr_times = current_groups.get(key, [])
            size_bytes = all_sizes_push.get(key, 0)
            display = fmt_size(size_bytes) if size_bytes else label
            oras_mean = mean(oras_times) if oras_times else 0
            v1_mean = mean(v1_times) if v1_times else 0
            curr_mean = mean(curr_times) if curr_times else 0
            v1_r = v1_mean / oras_mean if oras_mean > 0 else 0
            curr_r = curr_mean / oras_mean if oras_mean > 0 else 0
            cv1_r = curr_mean / v1_mean if v1_mean > 0 else 0
            if v1_r: v1_summary["push"].append(v1_r)
            if curr_r: current_summary["push"].append(curr_r)
            if cv1_r: cv1_summary["push"].append(cv1_r)
            lines.append(table_row([
                display,
                fmt_ms(oras_mean) if oras_times else "n/a",
                fmt_ms(v1_mean) if v1_times else "n/a",
                fmt_ms(curr_mean) if curr_times else "n/a",
                fmt_ratio(v1_r) if v1_r else "n/a",
                fmt_ratio(curr_r) if curr_r else "n/a",
                fmt_ratio(cv1_r) if cv1_r else "n/a",
            ], pw2))
        lines.append("")

    # ── Summary ──
    lines.append("## Summary")
    lines.append("")
    sw = [14, 22, 26, 22]
    sh = ["Operation", "v1 vs oras-go (avg)", "current vs oras-go (avg)", "current vs v1 (avg)"]
    lines.append(table_row(sh, sw))
    lines.append(table_sep(sw))
    for op, display in [("ping", "Ping"), ("catalog", "Catalog"), ("resolve", "Resolve"), ("tags", "Tags"), ("pull", "Pull"), ("push", "Push")]:
        v1_ratios = v1_summary.get(op, [])
        curr_ratios = current_summary.get(op, [])
        cv1_ratios = cv1_summary.get(op, [])
        if not v1_ratios and not curr_ratios and not cv1_ratios:
            continue
        v1_avg = mean(v1_ratios) if v1_ratios else 0
        curr_avg = mean(curr_ratios) if curr_ratios else 0
        cv1_avg = mean(cv1_ratios) if cv1_ratios else 0
        lines.append(table_row([
            display,
            fmt_ratio(v1_avg) if v1_avg else "n/a",
            fmt_ratio(curr_avg) if curr_avg else "n/a",
            fmt_ratio(cv1_avg) if cv1_avg else "n/a",
        ], sw))
    lines.append("")

    # ── Methodology ──
    lines.append("## Methodology")
    lines.append("")
    lines.append("- All clients tested against the same registry, run sequentially (no concurrent load)")
    lines.append("- koci v1 = published release 0.4.3")
    lines.append("- koci current = latest dev build")
    lines.append("- Warm-up iterations discarded to account for JVM JIT and connection pool warm-up")
    lines.append("- Each pull uses a fresh temp directory (no blob cache between iterations)")
    lines.append("- Each push uses unique random content to prevent registry deduplication; single-shot per size")
    lines.append("")

    report = "\n".join(lines)

    output_path = os.path.join(results_dir, "report.md")
    with open(output_path, "w") as f:
        f.write(report)

    print(report)
    print(f"\nReport written to {output_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
