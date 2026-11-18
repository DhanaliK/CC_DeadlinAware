import json
from pathlib import Path

p = json.loads(
    Path("data/python_edf_reference_seed7_size20_2workers.json")
    .read_text(encoding="utf-8")
)

outcomes = p["result"]["job_outcomes"]

print("Type:", type(outcomes).__name__)

if isinstance(outcomes, dict):
    print("Number of jobs:", len(outcomes))
    for key, value in list(outcomes.items())[:3]:
        print("\nJob key:", key)
        print("Record:", value)

elif isinstance(outcomes, list):
    print("Number of jobs:", len(outcomes))
    for item in outcomes[:3]:
        print("\nRecord:", item)
else:
    print("Value:", outcomes)
