import json
from pathlib import Path

files = [
    "data/python_edf_reference_seed7_size20_2workers.json",
    "data/cloudsim_edf_results_seed7_size20_2workers_multivm.json",
]

for filename in files:
    print("\n==========", filename, "==========")
    data = json.loads(Path(filename).read_text(encoding="utf-8"))

    print("Top-level keys:", list(data.keys()))

    for key, value in data.items():
        if isinstance(value, list) and value:
            print("List:", key)
            print("First item:", value[0])
            break
        elif isinstance(value, dict):
            print("Dictionary:", key, "keys:", list(value.keys()))
