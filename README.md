# CloudSim Deadline Scheduling Validation

A reproducibility-focused validation project comparing EDF, dynamic scale-out, and predictive scheduling policies using CloudSim 7.0.1.

## Structure

- `src/main/java/research/` — Java CloudSim simulation implementations
- `scripts/` — workload export, analysis, parity, and comparison utilities
- `data/` — checked-in workloads and summarised experiment results
- `docs/` — methodology and supporting documentation
- `pom.xml` — Maven build and CloudSim dependency configuration

Generated build and run artifacts (`target/`, `experiment_runs/`, `experiment_logs/`, and `checkpoints/`) are excluded from Git.

## Requirements

- JDK 21 or newer
- Maven 3.9+
- Python 3.10+
- Optional external reference project producing `CC_v3.2` workloads

## Build

```powershell
mvn clean compile
```

## Run a simulation

Set `CC_ROOT` to the directory containing the external reference project, then run:

```powershell
$env:CC_ROOT = "C:\path\to\CC_v3.2"
python .\run_experiment.py
```

Alternatively, set `cc_root` in `experiment_config.json`.

## Results

See `FINAL_CLOUDSIM_VALIDATION_REPORT.md` and the curated JSON/CSV summaries under `data/`.

## Reproducibility and ethics

- Do not publish machine-specific absolute paths or private data.
- Synthetic workloads are seed-based; use the stored hashes to verify exported workload integrity.
- The report distinguishes realistic online prediction from clairvoyant oracle experiments.
- No license is included yet. Add a license before publishing if reuse or citation rights need to be defined.
