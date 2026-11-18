import json
from pathlib import Path

files = [
    "data/python_edf_reference_seed7_size20.json",
    "data/cloudsim_edf_results_seed7_size20_1worker_multivm.json"
]

for filename in files:
    print("\n==========", filename, "==========")
    data = json.loads(Path(filename).read_text(encoding="utf-8"))
    print("Top-level keys:", list(data.keys()))
    print("Input hash:", data.get("input_hash", data.get("input_workload_hash")))
    if "result" in data:
        print("Result keys:", list(data["result"].keys()))
