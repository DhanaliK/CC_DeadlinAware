import csv
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CONFIG_PATH = ROOT / "experiment_config.json"
TOLERANCE_SECONDS = 0.00001


def load_json(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.load(f)


def save_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, default=str)


def run_command(command, cwd, env=None):
    print("\nRUNNING:", command)
    subprocess.run(command, cwd=str(cwd), env=env, check=True)


def main():
    config = load_json(CONFIG_PATH)

    experiment_name = config["experiment_name"]
    workload_cfg = config["workload"]
    sim_cfg = config["simulation"]
    cloudsim_cfg = config.get("cloudsim", {})
    output_cfg = config.get("output", {})

    source = workload_cfg["source"].upper()
    size = int(workload_cfg["size"])
    seed = int(workload_cfg["seed"])
    deadline_tier = workload_cfg["deadline_tier"].upper()
    dataset_split = workload_cfg.get("dataset_split", "TEST")

    policy = sim_cfg["scheduling_policy"].upper()
    workers_list = [int(x) for x in sim_cfg["workers_to_compare"]]

    if size <= 0:
        raise ValueError("Workload size must be positive.")
    if policy != "EDF":
        raise ValueError("This initial runner supports EDF only.")
    if not workers_list or any(x < 1 or x > 16 for x in workers_list):
        raise ValueError("Worker counts must be between 1 and 16.")
    if len(set(workers_list)) != len(workers_list):
        raise ValueError("Duplicate worker counts are not allowed.")

    max_replicas = int(sim_cfg["max_replicas"])
    min_replicas = int(sim_cfg["min_replicas"])

    if max_replicas < max(workers_list):
        raise ValueError("max_replicas must be at least the largest worker count.")
    if min_replicas < 1 or min_replicas > max_replicas:
        raise ValueError("Invalid min_replicas setting.")

    cc_root = Path(config["cc_root"]).resolve()
    if not cc_root.is_dir():
        raise FileNotFoundError(f"CC project not found: {cc_root}")

    output_base = ROOT / output_cfg.get("directory", "experiment_runs")
    run_dir = output_base / re.sub(r"[^A-Za-z0-9_.-]", "_", experiment_name)
    run_dir.mkdir(parents=True, exist_ok=True)

    # Make the CC_v3.2 modules importable.
    sys.path.insert(0, str(cc_root))

    from backend.app.datasets import load_workload
    from backend.app.experiments import run_simulation, SimulationParameters

    print("\n========== EXPERIMENT CONFIGURATION ==========")
    print("Experiment:", experiment_name)
    print("Workload:", source, size, "jobs")
    print("Seed:", seed)
    print("Deadline tier:", deadline_tier)
    print("Workers:", workers_list)
    print("Output:", run_dir)

    # Export the shared workload exactly once.
    workload_path = run_dir / "shared_workload.json"
    exporter = ROOT / "export_ccv2_workload.py"

    exporter_command = [
        sys.executable,
        str(exporter),
        "--cc-root", str(cc_root),
        "--source", source,
        "--size", str(size),
        "--seed", str(seed),
        "--deadline-tier", deadline_tier,
        "--dataset-split", str(dataset_split),
        "--output", str(workload_path),
    ]

    run_command(exporter_command, ROOT)

    exported = load_json(workload_path)
    exported_jobs = exported["jobs"]
    workload_hash = exported.get("input_hash")

    if not workload_hash:
        raise ValueError("Exporter did not provide input_hash.")

    # Load the matching Python workload from the same CC_v3.2 loader.
    python_jobs = load_workload(
        source=source,
        size=size,
        seed=seed,
        deadline_tier=deadline_tier,
    )

    if len(python_jobs) != len(exported_jobs):
        raise RuntimeError("Python and exported workload sizes differ.")

    print("\nShared workload hash:", workload_hash)
    print("Exported jobs:", len(exported_jobs))
    print("Python jobs:", len(python_jobs))

    # Configure Maven to use the active Java environment.
    env = os.environ.copy()
    java_home = env.get("JAVA_HOME")
    if java_home and Path(java_home).is_dir():
        env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")

    summaries = []

    for workers in workers_list:
        print("\n" + "=" * 60)
        print(f"TESTING {workers} WORKER(S)")
        print("=" * 60)

        # Python reference run.
        params = SimulationParameters(
            min_replicas=min_replicas,
            max_replicas=max_replicas,
            worker_capacity=int(sim_cfg["worker_capacity"]),
            cores_per_worker=float(sim_cfg["cores_per_worker"]),
            price_per_core_hour=float(sim_cfg["price_per_core_hour"]),
        )

        python_result = run_simulation(
            method=policy,
            workload_size=size,
            seed=seed,
            workers=workers,
            source=source,
            deadline_tier=deadline_tier,
            jobs=python_jobs,
            startup_delay=float(sim_cfg["startup_delay_seconds"]),
            simulation_parameters=params,
        )

        python_path = run_dir / f"python_edf_{workers}workers.json"
        save_json(python_path, python_result)

        # CloudSim run against the same exported workload file.
        cloudsim_path = run_dir / f"cloudsim_edf_{workers}workers.json"

        cloudsim_enabled = bool(cloudsim_cfg.get("enabled", True))
        cloudsim_result = None

        if cloudsim_enabled:
            # The current project paths contain no spaces, so the
            # CloudSim CLI's space-separated argument format is safe.
            if " " in str(workload_path) or " " in str(cloudsim_path):
                raise ValueError(
                    "Current CloudSim CLI cannot safely parse paths containing spaces."
                )

            command = (
                "& mvn exec:java "
                "'-Dexec.mainClass=research.CloudSimValidation' "
                f"'-Dexec.args={workload_path} {cloudsim_path} {workers}'"
            )

            run_command(
                ["powershell.exe", "-NoProfile", "-Command", command],
                ROOT,
                env,
            )
            cloudsim_result = load_json(cloudsim_path)

        # Compare the results.
        parity = {
            "workers": workers,
            "workload_hash_match": False,
            "job_count_match": False,
            "job_outcome_mismatches": 0,
            "timing_mismatches": 0,
            "max_start_difference_seconds": None,
            "max_finish_difference_seconds": None,
            "passed": False,
        }

        if cloudsim_result is not None:
            cloudsim_hash = cloudsim_result.get("input_workload_hash")
            parity["workload_hash_match"] = cloudsim_hash == workload_hash

            py_outcomes = {
                item["job_id"]: item
                for item in python_result["job_outcomes"]
            }
            cs_jobs = {
                item["job_id"]: item
                for item in cloudsim_result["jobs"]
            }

            parity["job_count_match"] = (
                len(py_outcomes) == len(cs_jobs)
                and set(py_outcomes) == set(cs_jobs)
            )

            start_diffs = []
            finish_diffs = []

            for job_id, py_job in py_outcomes.items():
                cs_job = cs_jobs.get(job_id)

                if cs_job is None:
                    parity["job_outcome_mismatches"] += 1
                    continue

                py_completed = py_job["status_at_cutoff"] == "COMPLETED"
                cs_completed = bool(cs_job["completed"])

                py_on_time = py_job["deadline_outcome"] == "ON_TIME"
                cs_on_time = bool(cs_job["on_time"]) if cs_completed else False

                if py_completed != cs_completed or py_on_time != cs_on_time:
                    parity["job_outcome_mismatches"] += 1

                if py_completed and cs_completed:
                    start_diff = abs(
                        float(py_job["started_at"]) - float(cs_job["start_time"])
                    )
                    finish_diff = abs(
                        float(py_job["completed_at"]) - float(cs_job["finish_time"])
                    )

                    start_diffs.append(start_diff)
                    finish_diffs.append(finish_diff)

                    if start_diff > TOLERANCE_SECONDS or finish_diff > TOLERANCE_SECONDS:
                        parity["timing_mismatches"] += 1

            parity["max_start_difference_seconds"] = max(start_diffs, default=0)
            parity["max_finish_difference_seconds"] = max(finish_diffs, default=0)

            parity["passed"] = (
                parity["workload_hash_match"]
                and parity["job_count_match"]
                and parity["job_outcome_mismatches"] == 0
                and parity["timing_mismatches"] == 0
            )

        summary = {
            "workers": workers,
            "python_completed": python_result.get("completed_jobs"),
            "python_on_time": python_result.get("on_time_jobs"),
            "python_late": python_result.get("late_jobs"),
            "python_unfinished": python_result.get("unfinished_jobs"),
            "python_makespan_seconds": python_result.get("makespan"),
            "python_modeled_cost": python_result.get("total_cost"),
            "cloudsim_summary": (
                cloudsim_result.get("summary") if cloudsim_result else None
            ),
            "parity": parity,
        }

        summaries.append(summary)

        print("\nPYTHON RESULTS")
        print("Completed:", summary["python_completed"])
        print("On time:", summary["python_on_time"])
        print("Late:", summary["python_late"])
        print("Makespan:", summary["python_makespan_seconds"])
        print("Modelled cost:", summary["python_modeled_cost"])

        if cloudsim_result is not None:
            print("\nPARITY RESULTS")
            print(json.dumps(parity, indent=2))

    final_report = {
        "experiment_name": experiment_name,
        "workload": {
            "source": source,
            "size": size,
            "seed": seed,
            "deadline_tier": deadline_tier,
            "input_hash": workload_hash,
        },
        "configuration": config,
        "results": summaries,
        "all_cloudsim_parity_passed": (
            all(x["parity"]["passed"] for x in summaries)
            if cloudsim_cfg.get("enabled", True)
            else None
        ),
    }

    report_path = run_dir / "experiment_summary.json"
    save_json(report_path, final_report)

    print("\n" + "=" * 60)
    print("EXPERIMENT FINISHED")
    print("=" * 60)
    print("Report:", report_path)
    print("Workload:", workload_path)

    if cloudsim_cfg.get("enabled", True):
        passed = sum(x["parity"]["passed"] for x in summaries)
        print(f"CloudSim parity: {passed}/{len(summaries)} worker configurations passed.")


if __name__ == "__main__":
    main()
