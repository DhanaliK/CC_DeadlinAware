import hashlib
import json
import os
import sys
from dataclasses import asdict
from pathlib import Path

cc_root = Path(os.environ.get("CC_ROOT", r"..\CC_v3.2")).resolve()
sys.path.insert(0, str(cc_root))

from backend.app.datasets import load_workload

file_path = Path(os.environ.get("WORKLOAD_FILE", "data/ccv2_workload_seed42.json"))
payload = json.loads(file_path.read_text(encoding="utf-8"))

# Verify the hash stored in the exported JSON.
canonical_export = json.dumps(
    payload["jobs"],
    sort_keys=True,
    separators=(",", ":"),
    ensure_ascii=True
)
export_hash = hashlib.sha256(
    canonical_export.encode("utf-8")
).hexdigest()

print("========== JSON INTEGRITY ==========")
print("Stored hash:   ", payload["input_hash"])
print("Calculated:    ", export_hash)
print("Hash matches:  ", payload["input_hash"] == export_hash)

# Regenerate the same workload using the original CC_v3.2 loader.
original_jobs = load_workload(
    payload["source"],
    len(payload["jobs"]),
    payload["seed"],
    payload["deadline_tier"]
)

original_records = sorted(
    (asdict(job) for job in original_jobs),
    key=lambda item: (item["arrival_time"], item["job_id"])
)

print("\n========== ORIGINAL WORKLOAD COMPARISON ==========")
print("Same job count: ", len(original_records) == len(payload["jobs"]))
print("Exact match:    ", original_records == payload["jobs"])

canonical_original = json.dumps(
    original_records,
    sort_keys=True,
    separators=(",", ":"),
    ensure_ascii=True
)
original_hash = hashlib.sha256(
    canonical_original.encode("utf-8")
).hexdigest()

print("Original hash:  ", original_hash)
print("Same hash:      ", original_hash == payload["input_hash"])

print("\n========== METADATA ==========")
print("Schema:         ", payload["schema_version"])
print("Source:         ", payload["source"])
print("Seed:           ", payload["seed"])
print("Deadline tier:  ", payload["deadline_tier"])
print("Dataset split:  ", payload["dataset_split"])
print("Jobs:           ", len(payload["jobs"]))

if not (
    payload["input_hash"] == export_hash
    and original_records == payload["jobs"]
    and original_hash == payload["input_hash"]
):
    raise SystemExit("\nVERIFICATION FAILED")

print("\nVERIFICATION PASSED")
