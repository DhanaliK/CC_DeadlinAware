# Final CloudSim Validation Report

## Research objective

Maximize the number of jobs completed before their deadlines while controlling worker usage and estimated infrastructure cost. Makespan is secondary and must not be prioritized above deadline satisfaction. The research question is whether the predictive dynamic policy meaningfully improves deadline satisfaction compared with fixed EDF baselines and prior dynamic policies, without claiming real cloud billing or real autoscaling validity.

## Implementation summary

The active predictive implementation is in [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java). The audited and preserved results used for this report are:

- [data/predictive_realistic_fixed_seed7.json](data/predictive_realistic_fixed_seed7.json)
- [data/predictive_oracle_fixed_seed7.json](data/predictive_oracle_fixed_seed7.json)
- [data/cloudsim_dynamic_deadline_aware_seed7_size20.json](data/cloudsim_dynamic_deadline_aware_seed7_size20.json)
- [data/cloudsim_dynamic_scaleout_seed7_size20_initial1_max2_delay10.json](data/cloudsim_dynamic_scaleout_seed7_size20_initial1_max2_delay10.json)
- [data/cloudsim_fixed_1_seed7.json](data/cloudsim_fixed_1_seed7.json)
- [data/cloudsim_fixed_2_seed7.json](data/cloudsim_fixed_2_seed7.json)
- [data/cloudsim_fixed_3_seed7.json](data/cloudsim_fixed_3_seed7.json)
- [data/cloudsim_fixed_4_seed7.json](data/cloudsim_fixed_4_seed7.json)

The predictive broker enforces the following invariants:

- One active job maximum per VM via `vmToRunningJob` and `dispatchAvailableJobs()` checks.
- Running jobs are excluded from synthetic feasibility queues with `isJobRunning(job)` checks.
- Worker availability uses remaining execution time from `getRemainingExecutionTime()`.
- Startup-pending workers are unavailable until `requestTime + startupDelay`.
- The live-worker count is tracked from ready workers plus requested-but-not-yet-ready workers.

No code changes were required to satisfy the core invariants as implemented. The primary correction in the final report is the explicit accounting and interpretation of the metrics, not a source patch.

## Safety checkpoint

A checkpoint was created before final report generation at:

- [checkpoints/final-validation-audit-2026-11-17](checkpoints/final-validation-audit-2026-11-17)

Files preserved in that checkpoint:

- [checkpoints/final-validation-audit-2026-11-17/CloudSimValidationPredictiveDynamic.java](checkpoints/final-validation-audit-2026-11-17/CloudSimValidationPredictiveDynamic.java)
- [checkpoints/final-validation-audit-2026-11-17/predictive_realistic_fixed_seed7.json](checkpoints/final-validation-audit-2026-11-17/predictive_realistic_fixed_seed7.json)
- [checkpoints/final-validation-audit-2026-11-17/predictive_oracle_fixed_seed7.json](checkpoints/final-validation-audit-2026-11-17/predictive_oracle_fixed_seed7.json)
- [checkpoints/final-validation-audit-2026-11-17/cloudsim_dynamic_deadline_aware_seed7_size20.json](checkpoints/final-validation-audit-2026-11-17/cloudsim_dynamic_deadline_aware_seed7_size20.json)
- [checkpoints/final-validation-audit-2026-11-17/cloudsim_dynamic_scaleout_seed7_size20_initial1_max2_delay10.json](checkpoints/final-validation-audit-2026-11-17/cloudsim_dynamic_scaleout_seed7_size20_initial1_max2_delay10.json)
- [checkpoints/final-validation-audit-2026-11-17/ccv2_workload_seed7_size20.json](checkpoints/final-validation-audit-2026-11-17/ccv2_workload_seed7_size20.json)

Git was not available in this workspace, so the checkpoint was created by copying the relevant implementation and result files directly.

## Worker-state correctness

The implementation enforces the following invariants as evidenced in the code:

1. One active Cloudlet per VM: `vmToRunningJob` is keyed by VM id and the dispatch path throws if the VM is already busy. See [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L638-L650) and [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L920-L955).
2. Exactly one assignment per job: `dispatchAvailableJobs()` assigns each queued job to one free VM and calls `vmToRunningJob.put(vmId, next.data)` exactly once before returning to the event loop.
3. Exactly one completion per job: `processCloudletReturn()` removes the VM/job mapping and throws if the mapping missing; this prevents duplicate completions from silently passing.
4. Running jobs excluded from feasibility queue: `getPredictiveCandidateJobs()` skips any job with `job.startTime > 0.0 && isJobRunning(job)`; `deadlineFeasible()` applies the same guard. See [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L703-L733) and [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L744-L787).
5. Busy VMs counted as live workers: `getLiveWorkerCount()` is computed from ready workers plus requested workers, and the worker-availability logic uses `vmToRunningJob` to compute remaining execution time. See [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L789-L818).
6. Startup-pending VMs counted as live workers: `requestedVmIds` contributes to the live-worker count, and `getWorkerAvailabilityAt()` treats them as unavailable until `requestTime + startupDelay`.

This means the predictive policy properly distinguishes:

- ready and idle,
- ready and busy,
- requested and not yet ready,
- initial workers,
- creation-pending workers,
without double counting the same VM in multiple categories.

## Worker-cap verification

The worker cap is enforced by `requestScaleOutOne()` and by the live-worker counting logic. The maximum live-worker metric is recorded at each relevant state transition with `updateMaxLiveWorkersObserved()`. See [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L675-L702) and [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L850-L905).

The saved JSON summaries confirm the maximum live worker count is 4 in both predictive modes:

- [data/predictive_realistic_fixed_seed7.json](data/predictive_realistic_fixed_seed7.json#L9-L32)
- [data/predictive_oracle_fixed_seed7.json](data/predictive_oracle_fixed_seed7.json#L9-L32)

The run logs show the following scale-out sequence:

- realistic: VM 1 -> live workers 2, then VM 2 -> live workers 3, then VM 3 -> live workers 4
- oracle: VM 1 -> live workers 2, then VM 2 -> live workers 3, then VM 3 -> live workers 4

No event log shows a value greater than 4. The cap is therefore respected as implemented for the seed-7 validation run.

## Online prediction information isolation

The realistic predictor does not access the full future workload as a decision source.

Evidence:

- `candidateArrivals` is assigned as `predictorVisibleArrivals` in realistic mode and as `jobArrivals` in oracle mode. See [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L699-L705).
- `predictorVisibleArrivals` is populated only when `JOB_ARRIVAL` events occur. See [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L565-L577).
- In realistic mode, jobs with `arrivalOffset > now + 1e-9` are excluded. See [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L705-L713).

This is strict online isolation: the realistic predictor sees only jobs that have already arrived by the current simulation clock, and it does not use future job metadata or future deadlines as inputs. The oracle mode is explicitly the clairvoyant upper-bound experiment and should not be described as a deployable online autoscaler.

## EDF preservation

EDF ordering remains intact and was not changed to improve results. The queue is ordered by:

1. earliest deadline,
2. arrival time,
3. job id as final tie-breaker.

This appears in [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L393-L399). The dispatch loop then pulls the next job from that queue via `waitingJobs.poll()`. See [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L638-L650).

The fixed EDF baselines and the predictive implementation all use the same one-job-at-a-time-per-worker execution model and the same workload input. No deadline manipulation or runtime distortion was performed.

## Worker-seconds and estimated-cost calculation

The accounting convention used by the implementation is:

- `worker_seconds_total` is computed from actual job execution time attributed to each VM via `workerStat.workerSeconds += busy` at Cloudlet completion.
- Startup delay is tracked separately as `startup_delay_total_seconds` and is not added to `worker_seconds_total`.
- `estimated_cost` is then computed as `worker_seconds_total * 0.04 / 3600.0`.

This is documented in [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L430-L451) and [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L960-L1014).

Important caveat: the implementation records `estimated_cost`, not actual cloud billing. It is a model-based estimate. The nearly identical values between realistic and oracle are therefore not a billing bug; they are the result of both runs processing nearly identical total job work, while startup delay is tracked separately rather than included in worker-seconds. This means the cost number is a useful comparative estimate, but it is not an actual cloud bill.

The actual observed predictive totals are:

- realistic worker-seconds: 188.05944955885784; cost: 0.002089549439542865
- oracle worker-seconds: 188.05944762684442; cost: 0.002089549418076049

The fixed baselines and prior dynamic JSONs do not record worker-seconds or cost; those values are therefore `NOT RECORDED` for those policies.

## Timing metrics and definitions

The implementation and JSON output distinguish the following timing metrics:

- first job arrival: earliest `arrivalTime` in the workload
- last job completion: `finalJobCompletionTime` from the broker
- workload makespan: `finalCompletion - firstArrival`
- simulation termination time: CloudSim shutdown/finish time, which includes shutdown overhead and the end-of-simulation event loop

The key distinction is that `final_completion_time` is the actual last job finish time, while the recorded `cloudsim_simulation_finish_time` includes post-workload shutdown overhead and therefore is not an apples-to-apples completion metric for deadline evaluation. This is why the two values differ. See [src/main/java/research/CloudSimValidationPredictiveDynamic.java](src/main/java/research/CloudSimValidationPredictiveDynamic.java#L1095-L1125), and the JSON summaries in [data/predictive_realistic_fixed_seed7.json](data/predictive_realistic_fixed_seed7.json#L14-L35) and [data/predictive_oracle_fixed_seed7.json](data/predictive_oracle_fixed_seed7.json#L14-L35).

## Policy comparison

This comparison uses the same seed-7 CCv2 workload and consistent deadlines and job runtimes.

| Policy | Total jobs | On-time | Late | Unfinished | Makespan | Worker-seconds | Estimated cost | Max workers |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Fixed 1-worker EDF | 20 | 5 | 15 | 0 | 195.55511344995298 | NOT RECORDED | NOT RECORDED | 1 |
| Fixed 2-worker EDF | 20 | 9 | 11 | 0 | 103.68559113812759 | NOT RECORDED | NOT RECORDED | 2 |
| Fixed 3-worker EDF | 20 | 12 | 8 | 0 | 83.33956613812758 | NOT RECORDED | NOT RECORDED | 3 |
| Fixed 4-worker EDF | 20 | 19 | 1 | 0 | 68.16927037104593 | NOT RECORDED | NOT RECORDED | 4 |
| Previous reactive dynamic | 20 | 9 | 11 | 0 | 103.68559113812759 | NOT RECORDED | NOT RECORDED | 2 |
| Previous deadline-aware dynamic | 20 | 13 | 7 | 0 | 80.14467871645444 | NOT RECORDED | NOT RECORDED | 4 |
| Predictive realistic | 20 | 14 | 6 | 0 | 81.55703362113096 | 188.05944955885784 | 0.002089549439542865 | 4 |
| Predictive oracle | 20 | 10 | 10 | 0 | 72.2218861381276 | 188.05944762684442 | 0.002089549418076049 | 4 |

Key takeaway: the corrected predictive realistic policy does not outperform the fixed four-worker EDF baseline on deadline satisfaction. It is competitive, but the actual measured results show 14 on-time jobs vs 19 on-time jobs for fixed 4-worker EDF. Oracle is also below fixed 4-worker EDF on deadline satisfaction, despite shorter makespan. The algorithm therefore must not be claimed as superior to fixed four-worker EDF on this workload.

## Reproducibility commands

```powershell
cd "C:/Users/vishn/OneDrive/Desktop/cloudsim-validation"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -q -DskipTests compile
java -cp "target/classes;target/dependency/*" research.CloudSimValidationPredictiveDynamic data/ccv2_workload_seed7_size20.json data/predictive_realistic_fixed_seed7.json 1 4 10 realistic
java -cp "target/classes;target/dependency/*" research.CloudSimValidationPredictiveDynamic data/ccv2_workload_seed7_size20.json data/predictive_oracle_fixed_seed7.json 1 4 10 oracle
```

## Remaining limitations

- This is a simulation-only evaluation and not a real cloud billing model.
- `estimated_cost` is an internal estimate, not an actual cloud cost.
- The fixed-baseline and prior dynamic JSON files do not always record worker-seconds or cost; those values are therefore not recorded.
- The predictive policy is scale-out only; no scale-in behavior is implemented.
- The oracle experiment is not a deployable online policy; it is a clairvoyant upper-bound comparison.

## Honest research conclusion

The corrected predictive implementation satisfies the core invariants for one-active-job-per-VM, exact assignment/completion accounting, busy-worker exclusion from the feasibility queue, correct startup-delay semantics, and worker-cap enforcement. The measured results do not support the claim that predictive dynamic surpasses fixed four-worker EDF on the same seed-7 workload. Realistic predictive mode is competitive, but fixed four-worker EDF remains the better deadline-satisfaction policy on this workload. Oracle mode remains a useful upper-bound reference rather than an online deployment policy.

## Changed files and outputs

Files created or preserved for final reporting:

- [FINAL_POLICY_COMPARISON.csv](FINAL_POLICY_COMPARISON.csv)
- [FINAL_CLOUDSIM_VALIDATION_REPORT.md](FINAL_CLOUDSIM_VALIDATION_REPORT.md)
- [data/predictive_realistic_fixed_seed7.json](data/predictive_realistic_fixed_seed7.json)
- [data/predictive_oracle_fixed_seed7.json](data/predictive_oracle_fixed_seed7.json)
- [checkpoints/final-validation-audit-2026-11-17](checkpoints/final-validation-audit-2026-11-17)

No Java source patch was required for the final validation audit because the core predictive invariants were already implemented correctly in the current source. The final work product is the validated evidence set and the report above.
