import argparse
import hashlib
import json
import sys
from dataclasses import asdict
from pathlib import Path

def main():
    parser = argparse.ArgumentParser()

    parser.add_argument("--cc-root", required=True)
    parser.add_argument("--size", type=int, required=True)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--deadline-tier", choices=["TIGHT", "MEDIUM", "RELAXED"], default="MEDIUM")
    parser.add_argument("--weights", required=True)
    parser.add_argument("--output", required=True)

    args = parser.parse_args()

    weights = tuple(float(x) for x in args.weights.split(","))

    if len(weights) != 4 or any(x < 0 for x in weights) or sum(weights) <= 0:
        raise ValueError("Weights must be four non-negative values with a positive sum.")

    if args.size < 1:
        raise ValueError("Size must be at least 1.")

    cc_root = Path(args.cc_root).resolve()
    if not cc_root.is_dir():
        raise FileNotFoundError(cc_root)

    sys.path.insert(0, str(cc_root))

    from backend.app.workloads import generate_workload

    deadline_factors = {
        "TIGHT": 1.25,
        "MEDIUM": 2.0,
        "RELAXED": 4.0
    }

    jobs = generate_workload(
        size=args.size,
        seed=args.seed,
        deadline_factor=deadline_factors[args.deadline_tier],
        profile_weights=weights
    )

    records = sorted(
        (asdict(job) for job in jobs),
        key=lambda item: (item["arrival_time"], item["job_id"])
    )

    required = {
        "job_id", "job_type", "arrival_time", "deadline",
        "estimated_runtime", "cpu_required", "memory_required", "priority"
    }

    for index, job in enumerate(records):
        missing = required - set(job)
        if missing:
            raise ValueError("Missing fields in job {}: {}".format(index, missing))

        if job["estimated_runtime"] <= 0:
            raise ValueError("Invalid runtime: {}".format(job["job_id"]))

        if job["deadline"] < job["arrival_time"]:
            raise ValueError("Invalid deadline: {}".format(job["job_id"]))

    ids = [job["job_id"] for job in records]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate job IDs.")

    canonical = json.dumps(
        records,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True
    )

    input_hash = hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    weight_label = "-".join(str(x).replace(".", "p") for x in weights)

    payload = {
        "schema_version": "ccv2.workload.v1",
        "source": "SYNTHETIC",
        "seed": args.seed,
        "deadline_tier": args.deadline_tier,
        "dataset_split": "TEST",
        "dataset_group_id": "SYNTHETIC_SEED:{}_WEIGHTS:{}".format(
            args.seed, weight_label
        ),
        "runtime_estimate_source": "synthetic model",
        "profile_weights": {
            "small": weights[0],
            "medium": weights[1],
            "heavy": weights[2],
            "urgent": weights[3]
        },
        "jobs": records,
        "input_hash": input_hash
    }

    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8"
    )

    counts = {}
    for job in records:
        counts[job["job_type"]] = counts.get(job["job_type"], 0) + 1

    print("WORKLOAD EXPORT SUCCESSFUL")
    print("Jobs:", len(records))
    print("Seed:", args.seed)
    print("Deadline tier:", args.deadline_tier)
    print("Weights:", weights)
    print("Job type counts:", counts)
    print("Input hash:", input_hash)
    print("Output:", output)

if __name__ == "__main__":
    main()