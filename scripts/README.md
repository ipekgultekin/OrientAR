# scripts/ — AR Navigation analysis tooling

This folder contains Python scripts for analyzing OrientAR field-test logs.

## Contents

* `analyze\_navigation\_logs.py` — extracts per-session metrics from FileLogger output, produces CSV and human-readable summary
* `.gitignore` — excludes raw logs and generated output from version control

## Requirements

* Python 3.8+ (any modern Python works)
* Standard library only — no `pip install` needed

## Usage

### Running the analysis

From the OrientAR project root:

```bash
python scripts/analyze_navigation_logs.py <input_dir> <output_dir>
```

Example:

```bash
mkdir scripts/logs scripts/output
# Copy field-test logs into scripts/logs/
python scripts/analyze_navigation_logs.py scripts/logs scripts/output
```

### Outputs

* `scripts/output/existing\_logs\_analysis.csv` — one row per session, columns for cycle timing, route metadata, completion %, etc.
* `scripts/output/existing\_logs\_summary.md` — human-readable summary with aggregate stats and per-session table

### Workflow for fresh walks

1. Walk a route on campus
2. Connect phone via USB, copy new `.log` file from `Android/data/com.example.orientar.navigation/files/OrientAR\_Logs/` to `scripts/logs/`
3. Re-run the script — outputs include the new walk
4. Use the updated CSV/summary in the testing report

## Notes

* Older logs (pre-SCRUM-56, before commit `940c46d`) lack `D/REFRESH\_GAP`, `D/PHANTOM\_ROUTE`, and `D/PROGRESS` tags. Their cycle-timing and routing data is unavailable. Newer logs have all three.
* Sessions with `duration\_sec < 30` are likely test launches, not real walks. Filter these out for system-test pass/fail evaluation.

