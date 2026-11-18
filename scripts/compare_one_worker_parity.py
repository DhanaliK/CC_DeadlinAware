import json
from pathlib import Path

python_file = Path("data/python_edf_reference_seed7_size20.json")
cloudsim_file = Path("data/cloudsim_edf_results_seed7_size20_1worker_multivm.json")

py = json.loads(python_file.read_text(encoding="utf-8"))
cs = json.loads(cloudsim_file.read_text(encoding="utf-8"))

py_jobs = {j["job_id"]: j for j in py["result"]["job_outcomes"]}
cs_jobs = {j["job_id"]: j for j in cs["jobs"]}

tolerance = 0.00001  # 10 microseconds

print("========== WORKLOAD IDENTITY ==========")
print("Python hash:  ", py.get("input_hash", py.get("input_workload_hash")))
print("CloudSim hash:", cs["input_workload_hash"])
print("Hash match:   ", py.get("input_hash", py.get("input_workload_hash")) == cs["input_workload_hash"])

print("\n========== JOB COUNT ==========")
print("Python jobs:  ", len(py_jobs))
print("CloudSim jobs:", len(cs_jobs))
print("Same job IDs: ", set(py_jobs) == set(cs_jobs))

if set(py_jobs) != set(cs_jobs):
    print("Missing in CloudSim:", sorted(set(py_jobs) - set(cs_jobs)))
    print("Extra in CloudSim:  ", sorted(set(cs_jobs) - set(py_jobs)))

start_diffs = []
finish_diffs = []
timing_failures = []
outcome_mismatches = []

for job_id in sorted(set(py_jobs) & set(cs_jobs)):
    p = py_jobs[job_id]
    c = cs_jobs[job_id]

    start_diff = abs(p["started_at"] - c["start_time"])
    finish_diff = abs(p["completed_at"] - c["finish_time"])

    start_diffs.append(start_diff)
    finish_diffs.append(finish_diff)

    if start_diff > tolerance or finish_diff > tolerance:
        timing_failures.append(
            (job_id, start_diff, finish_diff)
        )

    py_on_time = p["deadline_outcome"] == "ON_TIME"
    cs_on_time = c["on_time"]

    if py_on_time != cs_on_time:
        outcome_mismatches.append(
            (job_id, p["deadline_outcome"], cs_on_time)
        )

print("\n========== TIMING COMPARISON ==========")
print(f"Maximum start-time difference:  {max(start_diffs):.12f} seconds")
print(f"Maximum finish-time difference: {max(finish_diffs):.12f} seconds")
print(f"Tolerance:                     {tolerance:.8f} seconds")
print("Timing failures:", len(timing_failures))

for item in timing_failures:
    print("TIMING MISMATCH:", item)

print("\n========== DEADLINE OUTCOMES ==========")
print("Outcome mismatches:", len(outcome_mismatches))

for item in outcome_mismatches:
    print("OUTCOME MISMATCH:", item)

print("\n========== FINAL VERDICT ==========")
passed = (
    py.get("input_hash", py.get("input_workload_hash")) == cs["input_workload_hash"]
    and set(py_jobs) == set(cs_jobs)
    and len(timing_failures) == 0
    and len(outcome_mismatches) == 0
)

print("ONE-WORKER REGRESSION PARITY:", "PASS" if passed else "FAIL")

