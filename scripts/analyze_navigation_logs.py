"""
analyze_navigation_logs.py — extract per-session metrics from OrientAR field-test logs.

Usage:
    python analyze_navigation_logs.py <logs_dir> [output_dir]

If logs_dir is omitted, defaults to ./logs.
If output_dir is omitted, defaults to ./
Outputs:
    existing_logs_analysis.csv  — one row per session
    existing_logs_summary.md    — human-readable summary

Standard library only (Python 3.8+).
"""

import csv
import os
import re
import statistics
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path


# ============================================================================
# REGEX PATTERNS — tag-specific
# ============================================================================

# Common timestamp prefix: [HH:MM:SS.MMM][+SS.Xs][D/TAG]
TS_PREFIX = re.compile(r"^\[(\d{2}:\d{2}:\d{2}\.\d{3})\]\[\+([0-9.]+)s\]\[D/([A-Za-z_]+)\]")

# Cycle timing: D/REFRESH_GAP "Gap since last refresh: NNNNms"
REFRESH_GAP = re.compile(r"D/REFRESH_GAP\] Gap since last refresh:\s*(\d+)ms")

# Route metadata: D/PHANTOM_ROUTE mode=X snapDist=Y,Z accuracy=A,Bm
PHANTOM_ROUTE = re.compile(
    r"D/PHANTOM_ROUTE\]\s*(?:recalib\s+)?mode=(\w+).*?snapDist=([\d,]+)m.*?accuracy=([\d,]+)m"
)

# Progress: D/PROGRESS Index: raw=N, clamped=N, min=N, furthest=N, total=N
PROGRESS = re.compile(
    r"D/PROGRESS\] Index:\s*raw=(\d+),\s*clamped=(\d+),\s*min=(\d+),\s*furthest=(\d+),\s*total=(\d+)"
)

# Heading fusion samples — looking for any update to fused heading value
# Pattern is variable across versions; we focus on yaw offset reports
COORDINATE_ALIGNER_OFFSET = re.compile(
    r"D/CoordinateAligner\].*?yawOffset[Dd]eg=([\-\d.]+)"
)

# Session header (from file head)
SESSION_START = re.compile(r"Session Started:\s*([\d\-: ]+)")
DEVICE = re.compile(r"Device:\s*(\S.*)")


def euro_float(s: str) -> float:
    """Parse '9,3' (European decimal) or '9.3' as float."""
    return float(s.replace(",", "."))


# ============================================================================
# LOG ANALYSIS
# ============================================================================

class SessionMetrics:
    """One session's extracted metrics."""

    def __init__(self, path: Path):
        self.path = path
        self.filename = path.name
        self.session_start = None
        self.device = ""
        self.duration_sec = 0.0

        # Cycle timing
        self.cycle_gaps_ms: list[int] = []

        # Route metadata
        self.phantom_routes: list[dict] = []  # one entry per route launch/recalib
        self.modes_seen: set[str] = set()
        self.first_mode: str = ""
        self.first_snap_dist_m: float = 0.0
        self.first_accuracy_m: float = 0.0

        # Progress tracking
        self.progress_samples: list[tuple[int, int, int]] = []  # (raw, furthest, total)
        self.max_total_route_length: int = 0
        self.max_furthest_index: int = 0

        # Heading
        self.yaw_offsets_seen: list[float] = []

        # Counters
        self.line_count = 0
        self.has_phantom_tag = False
        self.has_progress_tag = False

    def derived_metrics(self) -> dict:
        """Compute aggregate metrics for CSV output."""
        d = {
            "filename": self.filename,
            "session_start": self.session_start or "",
            "device": self.device,
            "duration_sec": round(self.duration_sec, 1),
            "line_count": self.line_count,
            # Cycle timing
            "cycle_count": len(self.cycle_gaps_ms),
            "cycle_median_ms": int(statistics.median(self.cycle_gaps_ms)) if self.cycle_gaps_ms else 0,
            "cycle_p95_ms": (
                int(sorted(self.cycle_gaps_ms)[int(len(self.cycle_gaps_ms) * 0.95)])
                if len(self.cycle_gaps_ms) >= 5 else 0
            ),
            "cycle_max_ms": max(self.cycle_gaps_ms) if self.cycle_gaps_ms else 0,
            # Route
            "first_mode": self.first_mode,
            "modes_seen": ",".join(sorted(self.modes_seen)),
            "phantom_route_count": len(self.phantom_routes),
            "first_snap_dist_m": round(self.first_snap_dist_m, 1),
            "first_accuracy_m": round(self.first_accuracy_m, 1),
            # Progress
            "progress_sample_count": len(self.progress_samples),
            "max_route_length_nodes": self.max_total_route_length,
            "max_furthest_index": self.max_furthest_index,
            "completion_pct": (
                round(100 * self.max_furthest_index / self.max_total_route_length, 1)
                if self.max_total_route_length > 0 else 0.0
            ),
            # Compatibility flags
            "has_phantom_tag": self.has_phantom_tag,
            "has_progress_tag": self.has_progress_tag,
            "yaw_offsets_count": len(self.yaw_offsets_seen),
        }
        return d


def parse_log(path: Path) -> SessionMetrics:
    """Parse a single log file and extract metrics."""
    m = SessionMetrics(path)

    with open(path, "r", encoding="utf-8", errors="replace") as f:
        # Read header (first ~10 lines)
        for _ in range(15):
            line = f.readline()
            if not line:
                break
            ms = SESSION_START.search(line)
            if ms:
                m.session_start = ms.group(1).strip()
            md = DEVICE.search(line)
            if md:
                m.device = md.group(1).strip()

        # Reset and stream
        f.seek(0)
        last_relative_sec = 0.0

        for line in f:
            m.line_count += 1

            ts_match = TS_PREFIX.search(line)
            if ts_match:
                relative_sec = float(ts_match.group(2))
                last_relative_sec = max(last_relative_sec, relative_sec)

            # Cycle timing
            rg = REFRESH_GAP.search(line)
            if rg:
                m.cycle_gaps_ms.append(int(rg.group(1)))
                continue

            # Phantom route
            pr = PHANTOM_ROUTE.search(line)
            if pr:
                m.has_phantom_tag = True
                mode = pr.group(1)
                try:
                    snap = euro_float(pr.group(2))
                    acc = euro_float(pr.group(3))
                except ValueError:
                    continue
                m.modes_seen.add(mode)
                m.phantom_routes.append({"mode": mode, "snap_m": snap, "accuracy_m": acc})
                if not m.first_mode:
                    m.first_mode = mode
                    m.first_snap_dist_m = snap
                    m.first_accuracy_m = acc
                continue

            # Progress
            pg = PROGRESS.search(line)
            if pg:
                m.has_progress_tag = True
                raw = int(pg.group(1))
                furthest = int(pg.group(4))
                total = int(pg.group(5))
                m.progress_samples.append((raw, furthest, total))
                m.max_total_route_length = max(m.max_total_route_length, total)
                m.max_furthest_index = max(m.max_furthest_index, furthest)
                continue

            # Yaw offset
            ya = COORDINATE_ALIGNER_OFFSET.search(line)
            if ya:
                try:
                    m.yaw_offsets_seen.append(float(ya.group(1)))
                except ValueError:
                    pass

        m.duration_sec = last_relative_sec

    return m


# ============================================================================
# OUTPUT
# ============================================================================

def write_csv(sessions: list[SessionMetrics], out_path: Path) -> None:
    if not sessions:
        return

    rows = [s.derived_metrics() for s in sessions]
    fieldnames = list(rows[0].keys())

    with open(out_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(rows)


def write_summary(sessions: list[SessionMetrics], out_path: Path) -> None:
    rows = [s.derived_metrics() for s in sessions]

    # Aggregate stats
    total = len(rows)
    with_phantom = sum(1 for r in rows if r["has_phantom_tag"])
    with_progress = sum(1 for r in rows if r["has_progress_tag"])

    all_cycle_gaps = []
    for s in sessions:
        all_cycle_gaps.extend(s.cycle_gaps_ms)
    cycle_median_global = int(statistics.median(all_cycle_gaps)) if all_cycle_gaps else 0
    cycle_p95_global = (
        int(sorted(all_cycle_gaps)[int(len(all_cycle_gaps) * 0.95)])
        if len(all_cycle_gaps) >= 5 else 0
    )

    completions = [r["completion_pct"] for r in rows if r["max_route_length_nodes"] > 0]
    median_completion = round(statistics.median(completions), 1) if completions else 0.0

    mode_counts: dict[str, int] = defaultdict(int)
    for s in sessions:
        for m in s.modes_seen:
            mode_counts[m] += 1

    md = []
    md.append("# OrientAR — Existing Field-Test Log Analysis")
    md.append("")
    md.append(f"**Total sessions analyzed:** {total}")
    md.append(f"**Sessions with PHANTOM_ROUTE tag (post SCRUM-56):** {with_phantom} of {total}")
    md.append(f"**Sessions with PROGRESS tag (post SCRUM-56):** {with_progress} of {total}")
    md.append("")

    md.append("## Pipeline cycle timing (D/REFRESH_GAP)")
    md.append("")
    md.append(f"- **Total cycle samples across all sessions:** {len(all_cycle_gaps)}")
    md.append(f"- **Global median cycle gap:** {cycle_median_global} ms")
    md.append(f"- **Global p95 cycle gap:** {cycle_p95_global} ms")
    md.append("")
    md.append("Per Test Plan Section 5.4.4 / Table 22: median should be < 100 ms, p95 < 150 ms.")
    md.append("")

    md.append("## Phantom routing modes observed")
    md.append("")
    if mode_counts:
        for mode in sorted(mode_counts.keys()):
            md.append(f"- **{mode}**: seen in {mode_counts[mode]} sessions")
    else:
        md.append("(no PHANTOM_ROUTE tags found — all logs predate SCRUM-56)")
    md.append("")

    md.append("## Walk completion (where PROGRESS data available)")
    md.append("")
    md.append(f"- **Sessions with progress data:** {len(completions)}")
    md.append(f"- **Median completion percentage:** {median_completion}%")
    md.append("")
    md.append("Per Test Plan Section 5.3.4 / Table 21: ≥80% of walks should reach ≥90% length.")
    md.append("")

    if completions:
        ge_90 = sum(1 for c in completions if c >= 90)
        md.append(f"- **Sessions reaching ≥90% completion:** {ge_90} of {len(completions)} ({100*ge_90//len(completions)}%)")
        md.append("")

    md.append("## Per-session table")
    md.append("")
    md.append("| Session | Duration (s) | Cycle samples | Median gap (ms) | p95 gap (ms) | First mode | Snap (m) | Acc (m) | Route len | Furthest | Completion |")
    md.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        sname = r["filename"].replace("orientar_", "").replace(".log", "")
        md.append(
            f"| {sname} | {r['duration_sec']} | {r['cycle_count']} | "
            f"{r['cycle_median_ms']} | {r['cycle_p95_ms']} | {r['first_mode'] or '-'} | "
            f"{r['first_snap_dist_m'] or '-'} | {r['first_accuracy_m'] or '-'} | "
            f"{r['max_route_length_nodes'] or '-'} | {r['max_furthest_index'] or '-'} | "
            f"{r['completion_pct']}%"
            "|"
        )
    md.append("")

    md.append("## Notes for the testing report")
    md.append("")
    md.append("- Older logs (pre-SCRUM-56) lack PHANTOM_ROUTE and PROGRESS tags. Their cycle-timing data is still valid for performance metrics, but they cannot count toward route-completion or routing-mode coverage.")
    md.append("- Sessions with very short duration (< 30 s) likely represent app-launch-and-quit or test-launch sessions, not real navigation walks. Filter these out for system-test pass/fail evaluation.")
    md.append("- 'Furthest index' is the highest progress index reached during the session, regardless of momentary regressions caused by GPS drift.")
    md.append("")

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md))


# ============================================================================
# MAIN
# ============================================================================

def main():
    if len(sys.argv) < 2:
        logs_dir = Path("logs")
    else:
        logs_dir = Path(sys.argv[1])

    if len(sys.argv) >= 3:
        out_dir = Path(sys.argv[2])
    else:
        out_dir = Path(".")

    if not logs_dir.exists():
        print(f"ERROR: logs directory '{logs_dir}' does not exist", file=sys.stderr)
        return 1

    out_dir.mkdir(parents=True, exist_ok=True)

    log_files = sorted(logs_dir.glob("orientar_*.log"))
    if not log_files:
        print(f"ERROR: no orientar_*.log files found in {logs_dir}", file=sys.stderr)
        return 1

    print(f"Found {len(log_files)} log files. Parsing...")
    sessions = []
    for lf in log_files:
        s = parse_log(lf)
        sessions.append(s)
        print(f"  {lf.name}: {s.line_count} lines, {len(s.cycle_gaps_ms)} cycles, "
              f"first_mode={s.first_mode or '(none)'}, completion={s.derived_metrics()['completion_pct']}%")

    csv_path = out_dir / "existing_logs_analysis.csv"
    md_path = out_dir / "existing_logs_summary.md"

    write_csv(sessions, csv_path)
    write_summary(sessions, md_path)

    print(f"\nWritten:")
    print(f"  {csv_path}")
    print(f"  {md_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
