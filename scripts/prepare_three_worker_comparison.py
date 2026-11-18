from pathlib import Path

p = Path("compare_two_worker_parity.py")
s = p.read_text(encoding="utf-8")

s = s.replace(
    "python_edf_reference_seed7_size20_2workers.json",
    "python_edf_reference_seed7_size20_3workers.json"
)
s = s.replace(
    "cloudsim_edf_results_seed7_size20_2workers_multivm.json",
    "cloudsim_edf_results_seed7_size20_3workers_multivm.json"
)
s = s.replace(
    "TWO-WORKER PER-JOB PARITY",
    "THREE-WORKER PER-JOB PARITY"
)

Path("compare_three_worker_parity.py").write_text(s, encoding="utf-8")
print("Created three-worker comparison script.")
