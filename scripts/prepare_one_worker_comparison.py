from pathlib import Path

p = Path("compare_two_worker_parity.py")
s = p.read_text(encoding="utf-8")

s = s.replace(
    "python_edf_reference_seed7_size20_2workers.json",
    "python_edf_reference_seed7_size20.json"
)
s = s.replace(
    "cloudsim_edf_results_seed7_size20_2workers_multivm.json",
    "cloudsim_edf_results_seed7_size20_1worker_multivm.json"
)
s = s.replace(
    "TWO-WORKER PER-JOB PARITY",
    "ONE-WORKER REGRESSION PARITY"
)

Path("compare_one_worker_parity.py").write_text(s, encoding="utf-8")
print("Created one-worker comparison script.")
