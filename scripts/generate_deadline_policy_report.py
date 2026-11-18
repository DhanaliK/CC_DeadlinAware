import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"

SCENARIOS = [
    ("fixed_1_worker", DATA_DIR / "cloudsim_fixed_1_seed7.json"),
    ("fixed_2_worker", DATA_DIR / "cloudsim_fixed_2_seed7.json"),
    ("fixed_3_worker", DATA_DIR / "cloudsim_fixed_3_seed7.json"),
    ("fixed_4_worker", DATA_DIR / "cloudsim_fixed_4_seed7.json"),
    ("reactive_dynamic_1_to_2", DATA_DIR / "cloudsim_dynamic_scaleout_seed7_size20_initial1_max2_delay10.json"),
    ("deadline_aware_dynamic_1_to_4", DATA_DIR / "cloudsim_dynamic_deadline_aware_seed7_size20.json"),
]


def _load(path: Path):
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def _job_lateness(job):
    if not job.get("completed"):
        return 0.0
    finish = float(job.get("finish_time", 0.0))
    deadline = float(job.get("deadline", 0.0))
    return max(0.0, finish - deadline)


rows = []
for label, path in SCENARIOS:
    payload = _load(path)
    jobs = payload.get("jobs", [])
    summary = payload.get("summary", {})
    config = payload.get("configuration", {})

    total_jobs = int(summary.get("total_jobs", len(jobs)))
    on_time = int(summary.get("on_time_jobs", 0))
    late = int(summary.get("late_jobs", 0))
    unfinished = int(summary.get("unfinished_jobs", 0))
    lateness_values = [_job_lateness(job) for job in jobs]
    avg_lateness = sum(lateness_values) / total_jobs if total_jobs else 0.0
    max_lateness = max(lateness_values) if lateness_values else 0.0

    row = {
        "scenario": label,
        "total_jobs": total_jobs,
        "on_time_jobs": on_time,
        "late_jobs": late,
        "unfinished_jobs": unfinished,
        "on_time_pct": round((on_time / total_jobs) * 100.0, 2) if total_jobs else 0.0,
        "average_lateness_seconds": round(avg_lateness, 6),
        "max_lateness_seconds": round(max_lateness, 6),
        "makespan_seconds": round(float(summary.get("makespan", 0.0)), 6),
        "worker_count": int(config.get("worker_count", config.get("initial_worker_count", 0))),
        "max_worker_count": int(config.get("max_worker_count", config.get("worker_count", 0))),
        "scale_out_requests": int(config.get("dynamic_vm_requests", 0)),
        "startup_delay_seconds": float(config.get("startup_delay_seconds", 0.0)),
    }
    rows.append(row)

csv_path = DATA_DIR / "deadline_policy_comparison.csv"
with csv_path.open("w", newline="", encoding="utf-8") as fh:
    writer = csv.DictWriter(
        fh,
        fieldnames=[
            "scenario",
            "total_jobs",
            "on_time_jobs",
            "late_jobs",
            "unfinished_jobs",
            "on_time_pct",
            "average_lateness_seconds",
            "max_lateness_seconds",
            "makespan_seconds",
            "worker_count",
            "max_worker_count",
            "scale_out_requests",
            "startup_delay_seconds",
        ],
    )
    writer.writeheader()
    writer.writerows(rows)

json_path = DATA_DIR / "deadline_policy_comparison.json"
with json_path.open("w", encoding="utf-8") as fh:
    json.dump({"scenarios": rows}, fh, indent=2)

print(f"CSV report: {csv_path}")
print(f"JSON report: {json_path}")
