package research;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

public class CloudSimValidationPredictiveDynamic {

    private static final String WORKLOAD_FILE =
            "data/ccv2_workload_seed42.json";

    private static final int VM_MIPS = 1_000_000;
    private static final int VM_PES = 2;
    private static final int VM_RAM = 4096;
    private static final long VM_BW = 10000;
    private static final long VM_STORAGE = 100000;
    private static final double PRICE_PER_CORE_HOUR = 0.04;

    public static void main(String[] args) {

        try {
            Path workloadPath = args.length >= 1
                    ? Path.of(args[0])
                    : Path.of(WORKLOAD_FILE);

            Path outputPath = args.length >= 2
                    ? Path.of(args[1])
                    : Path.of("data/cloudsim_predictive_dynamic_results.json");

            int workerCount = args.length >= 3
                    ? Integer.parseInt(args[2])
                    : 1;

            int maxWorkers = args.length >= 4
                    ? Integer.parseInt(args[3])
                    : 4;

            double startupDelay = args.length >= 5
                    ? Double.parseDouble(args[4])
                    : 10.0;

            PredictiveMode mode = args.length >= 6
                    ? PredictiveMode.valueOf(args[5].trim().toUpperCase(Locale.ROOT))
                    : PredictiveMode.REALISTIC;

            if (workerCount < 1 || workerCount > 16) {
                throw new IllegalArgumentException(
                        "Initial worker count must be between 1 and 16"
                );
            }

            if (maxWorkers < workerCount || maxWorkers > 16) {
                throw new IllegalArgumentException(
                        "Maximum workers must be >= initial workers and <= 16"
                );
            }

            if (!Double.isFinite(startupDelay) || startupDelay < 0) {
                throw new IllegalArgumentException(
                        "Startup delay must be finite and non-negative"
                );
            }

            System.out.println("Predictive mode: " + mode);
            System.out.println("Initial workers: " + workerCount);
            System.out.println("Maximum workers: " + maxWorkers);
            System.out.println("Configured startup delay: "
                    + startupDelay + " seconds");

            Workload workload = loadWorkload(workloadPath);

            Log.println("Starting CloudSim predictive workload integration...");

            CloudSim.init(1, Calendar.getInstance(), false);
            createDatacenter("Research_Datacenter", maxWorkers);

            PredictiveBroker broker = new PredictiveBroker(
                    "Research_Broker",
                    workerCount,
                    maxWorkers,
                    startupDelay,
                    mode
            );

            int brokerId = broker.getId();
            List<Vm> vmList = new ArrayList<>();

            for (int vmId = 0; vmId < workerCount; vmId++) {
                Vm vm = new Vm(
                        vmId,
                        brokerId,
                        VM_MIPS,
                        VM_PES,
                        VM_RAM,
                        VM_BW,
                        VM_STORAGE,
                        "Xen",
                        new CloudletSchedulerSpaceShared()
                );

                vmList.add(vm);
            }

            broker.submitGuestList(vmList);

            List<JobArrival> arrivals = new ArrayList<>();
            UtilizationModel utilization = new UtilizationModelFull();
            int cloudletId = 0;

            for (JobData data : workload.jobs) {
                long cloudletLength = Math.max(1, Math.round(
                        data.estimatedRuntime * VM_MIPS
                ));

                Cloudlet cloudlet = new Cloudlet(
                        cloudletId,
                        cloudletLength,
                        1,
                        300,
                        300,
                        utilization,
                        utilization,
                        utilization
                );

                cloudlet.setUserId(brokerId);
                data.cloudletId = cloudletId;

                arrivals.add(new JobArrival(
                        data,
                        cloudlet,
                        data.arrivalTime
                ));

                cloudletId++;
            }

            broker.setJobArrivals(arrivals);

            double simulationFinishTime = CloudSim.startSimulation();
            CloudSim.stopSimulation();

            printResults(workload, broker, simulationFinishTime);
            writeResults(workload, broker, simulationFinishTime, outputPath);

        } catch (Exception exception) {
            System.err.println("PREDICTIVE CLOUDSIM VALIDATION FAILED:");
            exception.printStackTrace();
            System.exit(1);
        }
    }

    private static Workload loadWorkload(Path path) throws IOException {
        String json = Files.readString(path);
        Workload workload = new Workload();

        workload.schemaVersion = stringField(json, "schema_version");
        workload.source = stringField(json, "source");
        workload.seed = (int) numberField(json, "seed");
        workload.deadlineTier = stringField(json, "deadline_tier");
        workload.inputHash = stringField(json, "input_hash");

        if (!"ccv2.workload.v1".equals(workload.schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported workload schema: " + workload.schemaVersion
            );
        }

        Matcher jobsArrayMatcher = Pattern.compile(
                "\"jobs\"\\s*:\\s*\\[(.*?)]\\s*,\\s*\"input_hash\"",
                Pattern.DOTALL
        ).matcher(json);

        if (!jobsArrayMatcher.find()) {
            throw new IllegalArgumentException(
                    "Could not locate jobs array in workload JSON."
            );
        }

        String jobsArray = jobsArrayMatcher.group(1);
        Matcher jobMatcher = Pattern.compile(
                "\\{(.*?)\\}",
                Pattern.DOTALL
        ).matcher(jobsArray);

        while (jobMatcher.find()) {
            String object = jobMatcher.group(1);
            JobData job = new JobData();

            job.jobId = stringField(object, "job_id");
            job.jobType = stringField(object, "job_type");
            job.arrivalTime = numberField(object, "arrival_time");
            job.deadline = numberField(object, "deadline");
            job.estimatedRuntime = numberField(object, "estimated_runtime");
            job.cpuRequired = numberField(object, "cpu_required");
            job.memoryRequired = (int) numberField(object, "memory_required");
            job.priority = (int) numberField(object, "priority");

            if (job.arrivalTime < 0
                    || job.estimatedRuntime <= 0
                    || job.deadline < job.arrivalTime) {
                throw new IllegalArgumentException(
                        "Invalid workload values for " + job.jobId
                );
            }

            workload.jobs.add(job);
        }

        if (workload.jobs.isEmpty()) {
            throw new IllegalArgumentException(
                    "No jobs were found in the workload."
            );
        }

        return workload;
    }

    private static String stringField(String json, String field) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(field)
                        + "\"\\s*:\\s*\"([^\"]*)\""
        );

        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Missing string field: " + field
            );
        }

        return matcher.group(1);
    }

    private static double numberField(String json, String field) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(field)
                        + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)"
        );

        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Missing numeric field: " + field
            );
        }

        return Double.parseDouble(matcher.group(1));
    }

    private static Datacenter createDatacenter(String name, int maxWorkers) throws Exception {
        List<Host> hostList = new ArrayList<>();
        List<Pe> peList = new ArrayList<>();

        for (int i = 0; i < maxWorkers * VM_PES; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(VM_MIPS)));
        }

        Host host = new Host(
                0,
                new RamProvisionerSimple(maxWorkers * VM_RAM),
                new BwProvisionerSimple(maxWorkers * VM_BW),
                maxWorkers * VM_STORAGE,
                peList,
                new VmSchedulerTimeShared(peList)
        );

        hostList.add(host);

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86",
                "Linux",
                "Xen",
                hostList,
                10.0,
                0.0,
                0.0,
                0.0,
                0.0
        );

        return new Datacenter(
                name,
                characteristics,
                new VmAllocationPolicySimple(hostList),
                new LinkedList<Storage>(),
                0
        );
    }

    private enum PredictiveMode {
        REALISTIC,
        ORACLE
    }

    private static class Workload {
        String schemaVersion;
        String source;
        int seed;
        String deadlineTier;
        String inputHash;
        List<JobData> jobs = new ArrayList<>();
    }

    private static class JobData {
        String jobId;
        String jobType;
        double arrivalTime;
        double deadline;
        double estimatedRuntime;
        double cpuRequired;
        int memoryRequired;
        int priority;

        int cloudletId;
        double startTime;
        double finishTime;
        boolean onTime;
        boolean completed;
        double waitingTime;
        double lateness;
    }

    private static class JobArrival {
        final JobData data;
        final Cloudlet cloudlet;
        final double arrivalOffset;

        JobArrival(JobData data, Cloudlet cloudlet, double arrivalOffset) {
            this.data = data;
            this.cloudlet = cloudlet;
            this.arrivalOffset = arrivalOffset;
        }
    }

    private static class WorkerStat {
        final int vmId;
        double requestedAt;
        double readyAt;
        double workerSeconds;
        int jobCount;
        boolean ready;

        WorkerStat(int vmId, double requestedAt, double readyAt) {
            this.vmId = vmId;
            this.requestedAt = requestedAt;
            this.readyAt = readyAt;
            this.ready = readyAt <= requestedAt || Double.isFinite(readyAt);
            this.workerSeconds = 0.0;
            this.jobCount = 0;
        }
    }

    private static class PredictiveBroker extends DatacenterBroker {

        private final List<JobArrival> jobArrivals = new ArrayList<>();
        private final List<JobArrival> predictorVisibleArrivals = new ArrayList<>();
        private final PriorityQueue<JobArrival> waitingJobs = new PriorityQueue<>(
                Comparator.comparingDouble((JobArrival a) -> a.data.deadline)
                        .thenComparingDouble(a -> a.arrivalOffset)
                        .thenComparing(a -> a.data.jobId)
        );

        private final PriorityQueue<Integer> freeVmIds = new PriorityQueue<>();
        private final Set<Integer> readyVmIds = new HashSet<>();
        private final Set<Integer> requestedVmIds = new HashSet<>();
        private final Set<Integer> failedVmIds = new HashSet<>();
        private final Map<Integer, JobData> vmToRunningJob = new HashMap<>();
        private final Map<Integer, Double> vmAvailabilityEstimate = new HashMap<>();
        private final List<String> scaleDecisionReasons = new ArrayList<>();

        private final Map<Integer, WorkerStat> workerStats = new HashMap<>();
        private final Map<Integer, Double> vmRequestTime = new HashMap<>();
        private final Map<Integer, Double> vmReadyTime = new HashMap<>();
        private final Map<String, Double> startedJobRuntime = new HashMap<>();

        private final int workerCount;
        private final int maxWorkers;
        private final double startupDelay;
        private final PredictiveMode mode;

        private int nextVmIdToCreate;
        private int dynamicVmRequestCount = 0;
        private int arrivalsProcessed = 0;
        private int maxLiveWorkersObserved = 0;
        private boolean arrivalEventsScheduled = false;
        private boolean scaleOutFailed = false;
        private double simulationOriginTime = 0.0;
        private double finalJobCompletionTime = 0.0;
        private double totalStartupDelaySeconds = 0.0;

        PredictiveBroker(
                String name,
                int workerCount,
                int maxWorkers,
                double startupDelay,
                PredictiveMode mode
        ) throws Exception {
            super(name);
            this.workerCount = workerCount;
            this.maxWorkers = maxWorkers;
            this.startupDelay = startupDelay;
            this.mode = mode;
            this.nextVmIdToCreate = workerCount;

            for (int vmId = 0; vmId < workerCount; vmId++) {
                freeVmIds.add(vmId);
                readyVmIds.add(vmId);
                vmReadyTime.put(vmId, 0.0);
                workerStats.put(vmId, new WorkerStat(vmId, 0.0, 0.0));
            }
        }

        public void setJobArrivals(List<JobArrival> arrivals) {
            this.jobArrivals.clear();
            this.jobArrivals.addAll(arrivals);
        }

        public int getWorkerCount() {
            return workerCount;
        }

        public int getMaxWorkers() {
            return maxWorkers;
        }

        public int getReadyWorkerCount() {
            return readyVmIds.size();
        }

        public int getDynamicVmRequestCount() {
            return dynamicVmRequestCount;
        }

        public int getFailedWorkerCount() {
            return failedVmIds.size();
        }

        public double getFinalJobCompletionTime() {
            return finalJobCompletionTime;
        }

        public double getSimulationOriginTime() {
            return simulationOriginTime;
        }

        public double getTotalWorkerSeconds() {
            double total = 0.0;
            for (WorkerStat stat : workerStats.values()) {
                total += stat.workerSeconds;
            }
            return total;
        }

        public double getEstimatedCost() {
            return getTotalWorkerSeconds() * (PRICE_PER_CORE_HOUR / 3600.0);
        }

        public double getStartupDelaySeconds() {
            return totalStartupDelaySeconds;
        }

        @Override
        protected void submitCloudlets() {
            if (arrivalEventsScheduled) {
                return;
            }

            arrivalEventsScheduled = true;
            simulationOriginTime = CloudSim.clock();

            System.out.printf(
                    "%nWorkload simulation origin: %.6f seconds%n",
                    simulationOriginTime
            );

            for (JobArrival arrival : jobArrivals) {
                schedule(
                        getId(),
                        arrival.arrivalOffset,
                        ResearchEventTag.JOB_ARRIVAL,
                        arrival
                );

                System.out.printf(
                        "Scheduled %s at workload time %.6f seconds%n",
                        arrival.data.jobId,
                        arrival.arrivalOffset
                );
            }
        }

        @Override
        protected void processVmCreateAck(SimEvent event) {
            int[] data = (int[]) event.getData();
            int vmId = data[1];
            boolean success = data[2] == 1;

            boolean isDynamicVm = requestedVmIds.contains(vmId);
            super.processVmCreateAck(event);

            if (!isDynamicVm) {
                return;
            }

            if (!success) {
                scaleOutFailed = true;
                failedVmIds.add(vmId);
                requestedVmIds.remove(vmId);
                vmRequestTime.remove(vmId);
                workerStats.remove(vmId);
                System.out.printf(
                        "%nPREDICTIVE VM CREATION FAILED: VM %d%n",
                        vmId
                );
                finishIfAllJobsCompleted();
                return;
            }

            System.out.printf(
                    "%nPREDICTIVE VM CREATED: VM %d | startup delay %.6f seconds%n",
                    vmId,
                    startupDelay
            );

            totalStartupDelaySeconds += startupDelay;
            schedule(
                    getId(),
                    startupDelay,
                    ResearchEventTag.VM_READY,
                    vmId
            );
        }

        @Override
        protected void processOtherEvent(SimEvent event) {
            if (event.getTag() == ResearchEventTag.JOB_ARRIVAL) {
                JobArrival arrival = (JobArrival) event.getData();
                arrivalsProcessed++;
                waitingJobs.add(arrival);
                predictorVisibleArrivals.add(arrival);

                System.out.printf(
                        "%nJOB ARRIVAL: %s at workload time %.6f%n",
                        arrival.data.jobId,
                        CloudSim.clock() - simulationOriginTime
                );

                dispatchAvailableJobs();

            } else if (event.getTag() == ResearchEventTag.VM_READY) {
                int vmId = (Integer) event.getData();
                boolean isInitialVm = vmId < workerCount;

                if (!isInitialVm && !requestedVmIds.contains(vmId)) {
                    throw new IllegalStateException(
                            "VM_READY received for an unrequested VM: " + vmId
                    );
                }

                if (!readyVmIds.add(vmId)) {
                    if (isInitialVm) {
                        return;
                    }
                    throw new IllegalStateException(
                            "VM became ready more than once: " + vmId
                    );
                }

                if (!isInitialVm) {
                    freeVmIds.add(vmId);
                    requestedVmIds.remove(vmId);
                }

                vmReadyTime.put(vmId, CloudSim.clock() - simulationOriginTime);

                WorkerStat stat = workerStats.get(vmId);
                if (stat != null) {
                    stat.readyAt = CloudSim.clock() - simulationOriginTime;
                    stat.ready = true;
                }

                updateMaxLiveWorkersObserved();

                System.out.printf(
                        "%nPREDICTIVE VM READY: VM %d | workload time %.6f | ready workers %d%n",
                        vmId,
                        CloudSim.clock() - simulationOriginTime,
                        readyVmIds.size()
                );

                if (!isInitialVm) {
                    dispatchAvailableJobs();
                    finishIfAllJobsCompleted();
                }

            } else {
                super.processOtherEvent(event);
            }
        }

        private void dispatchAvailableJobs() {
            while (!waitingJobs.isEmpty() && !freeVmIds.isEmpty()) {
                JobArrival next = waitingJobs.poll();
                int vmId = freeVmIds.poll();

                if (vmToRunningJob.containsKey(vmId)) {
                    throw new IllegalStateException(
                            "VM " + vmId + " is already busy while being dispatched"
                    );
                }

                next.data.startTime = CloudSim.clock() - simulationOriginTime;
                next.cloudlet.setGuestId(vmId);
                vmToRunningJob.put(vmId, next.data);
                getCloudletList().add(next.cloudlet);

                System.out.printf(
                        "EDF DISPATCH: %s -> VM %d (deadline %.6f)%n",
                        next.data.jobId,
                        vmId,
                        next.data.deadline
                );

                super.submitCloudlets();
            }

            if (!waitingJobs.isEmpty()) {
                enforcePredictiveScaleOut();
            }
        }

        private void enforcePredictiveScaleOut() {
            if (scaleOutFailed || nextVmIdToCreate >= maxWorkers) {
                return;
            }

            List<JobData> knownJobs = getPredictiveCandidateJobs();
            int requiredWorkers = computeRequiredWorkers(knownJobs);
            int liveWorkers = getLiveWorkerCount();

            if (requiredWorkers <= liveWorkers) {
                return;
            }

            if (liveWorkers < maxWorkers) {
                requestScaleOutOne();
            }
        }

        private List<JobData> getPredictiveCandidateJobs() {
            List<JobData> known = new ArrayList<>();
            double now = CloudSim.clock() - simulationOriginTime;
            List<JobArrival> candidateArrivals = mode == PredictiveMode.REALISTIC
                    ? predictorVisibleArrivals
                    : jobArrivals;

            for (JobArrival arrival : candidateArrivals) {
                JobData job = arrival.data;
                if (job.completed) {
                    continue;
                }

                if (mode == PredictiveMode.REALISTIC) {
                    if (arrival.arrivalOffset > now + 1e-9) {
                        continue;
                    }
                }

                if (job.startTime > 0.0 && isJobRunning(job)) {
                    continue;
                }

                known.add(job);
            }

            known.sort((left, right) -> {
                int cmp = Double.compare(left.deadline, right.deadline);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Double.compare(left.arrivalTime, right.arrivalTime);
                if (cmp != 0) {
                    return cmp;
                }
                return left.jobId.compareTo(right.jobId);
            });
            return known;
        }

        private int computeRequiredWorkers(List<JobData> knownJobs) {
            if (knownJobs.isEmpty()) {
                return Math.max(freeVmIds.size(), 1);
            }

            double now = CloudSim.clock() - simulationOriginTime;
            int maxCandidate = maxWorkers;
            int baseCapacity = Math.max(Math.max(readyVmIds.size(), freeVmIds.size()), 1);

            for (int candidate = baseCapacity; candidate <= maxCandidate; candidate++) {
                if (deadlineFeasible(candidate, now, knownJobs)) {
                    return candidate;
                }
            }

            return maxCandidate;
        }

        private boolean deadlineFeasible(int candidateWorkers, double now, List<JobData> knownJobs) {
            List<PendingJob> jobs = new ArrayList<>();

            for (JobData job : knownJobs) {
                if (job.completed || isJobRunning(job)) {
                    continue;
                }

                double remaining = getRemainingExecutionTime(job, now);
                if (remaining <= 0.0) {
                    continue;
                }

                jobs.add(new PendingJob(job, remaining));
            }

            if (jobs.isEmpty()) {
                return true;
            }

            jobs.sort(Comparator
                    .comparingDouble((PendingJob j) -> j.job.deadline)
                    .thenComparingDouble(j -> j.job.arrivalTime)
                    .thenComparing(j -> j.job.jobId));

            Map<Integer, Double> availabilityByWorker = buildWorkerAvailability(now, candidateWorkers);
            double[] workerAvailable = new double[candidateWorkers];
            for (int i = 0; i < candidateWorkers; i++) {
                workerAvailable[i] = availabilityByWorker.getOrDefault(i, now + startupDelay);
            }

            for (PendingJob pending : jobs) {
                int selectedWorker = 0;
                double selectedAvailable = Double.POSITIVE_INFINITY;

                for (int i = 0; i < candidateWorkers; i++) {
                    if (workerAvailable[i] < selectedAvailable) {
                        selectedAvailable = workerAvailable[i];
                        selectedWorker = i;
                    }
                }

                double startTime = Math.max(pending.job.arrivalTime, selectedAvailable);
                double finishTime = startTime + pending.remaining;
                if (finishTime > pending.job.deadline + 1e-9) {
                    return false;
                }
                workerAvailable[selectedWorker] = finishTime;
            }

            return true;
        }

        private Map<Integer, Double> buildWorkerAvailability(double now, int candidateWorkers) {
            Map<Integer, Double> availability = new HashMap<>();

            for (int vmId = 0; vmId < candidateWorkers; vmId++) {
                availability.put(vmId, getWorkerAvailabilityAt(now, vmId));
            }

            return availability;
        }

        private double getWorkerAvailabilityAt(double now, int vmId) {
            if (vmToRunningJob.containsKey(vmId)) {
                JobData active = vmToRunningJob.get(vmId);
                double remaining = getRemainingExecutionTime(active, now);
                return now + remaining;
            }

            if (vmId < workerCount) {
                return now;
            }

            if (requestedVmIds.contains(vmId)) {
                double requestTime = vmRequestTime.getOrDefault(vmId, now);
                return Math.max(now, requestTime + startupDelay);
            }

            if (readyVmIds.contains(vmId)) {
                return now;
            }

            return now + startupDelay;
        }

        private double getRemainingExecutionTime(JobData job, double now) {
            if (job.startTime <= 0.0) {
                return job.estimatedRuntime;
            }
            return Math.max(0.0, job.estimatedRuntime - (now - job.startTime));
        }

        private boolean isJobRunning(JobData job) {
            for (JobData runningJob : vmToRunningJob.values()) {
                if (runningJob == job || (runningJob.jobId != null && runningJob.jobId.equals(job.jobId))) {
                    return true;
                }
            }
            return false;
        }

        private int getLiveWorkerCount() {
            return readyVmIds.size() + requestedVmIds.size();
        }

        private void updateMaxLiveWorkersObserved() {
            maxLiveWorkersObserved = Math.max(maxLiveWorkersObserved, getLiveWorkerCount());
        }

        private void requestScaleOutOne() {
            if (scaleOutFailed || nextVmIdToCreate >= maxWorkers) {
                return;
            }

            if (getDatacenterIdsList().isEmpty()) {
                scaleOutFailed = true;
                scaleDecisionReasons.add("scale_out_rejected:no_datacenter");
                System.out.println(
                        "PREDICTIVE SCALE-OUT FAILED: no datacenter is available"
                );
                return;
            }

            int vmId = nextVmIdToCreate;
            while (readyVmIds.contains(vmId)
                    || requestedVmIds.contains(vmId)
                    || workerStats.containsKey(vmId)) {
                vmId++;
                if (vmId >= maxWorkers) {
                    scaleDecisionReasons.add("scale_out_rejected:worker_cap_reached");
                    return;
                }
            }
            nextVmIdToCreate = vmId + 1;

            Vm vm = new Vm(
                    vmId,
                    getId(),
                    VM_MIPS,
                    VM_PES,
                    VM_RAM,
                    VM_BW,
                    VM_STORAGE,
                    "Xen",
                    new CloudletSchedulerSpaceShared()
            );

            getGuestList().add(vm);
            requestedVmIds.add(vmId);
            vmRequestTime.put(vmId, CloudSim.clock() - simulationOriginTime);
            workerStats.put(vmId, new WorkerStat(vmId,
                    CloudSim.clock() - simulationOriginTime,
                    Double.NaN
            ));
            updateMaxLiveWorkersObserved();

            dynamicVmRequestCount++;
            String decision = String.format(
                    "PREDICTIVE SCALE-OUT REQUEST: VM %d | current ready workers %d | provisioning workers %d | live workers %d | workload time %.6f",
                    vmId,
                    readyVmIds.size(),
                    requestedVmIds.size(),
                    getLiveWorkerCount(),
                    CloudSim.clock() - simulationOriginTime
            );
            scaleDecisionReasons.add(decision);
            System.out.printf("%n%s%n", decision);

            createVmsInDatacenter(getDatacenterIdsList().get(0));
        }

        @Override
        protected void processCloudletReturn(SimEvent event) {
            Cloudlet completed = (Cloudlet) event.getData();
            getCloudletReceivedList().add(completed);

            if (cloudletsSubmitted <= 0) {
                throw new IllegalStateException(
                        "CloudSim submitted-job counter became invalid"
                );
            }

            cloudletsSubmitted--;

            int completedVmId = completed.getGuestId();
            JobData completedJob = vmToRunningJob.remove(completedVmId);
            if (completedJob == null) {
                for (Map.Entry<Integer, JobData> entry : vmToRunningJob.entrySet()) {
                    if (entry.getValue().cloudletId == completed.getCloudletId()) {
                        completedVmId = entry.getKey();
                        completedJob = vmToRunningJob.remove(completedVmId);
                        break;
                    }
                }
            }

            if (completedJob == null) {
                throw new IllegalStateException(
                        "No active VM/job association for Cloudlet " + completed.getCloudletId()
                );
            }

            if (completedVmId < 0 || completedVmId >= maxWorkers || !readyVmIds.contains(completedVmId)) {
                throw new IllegalStateException(
                        "Completed job came from an invalid or unready VM: " + completedVmId
                );
            }

            if (freeVmIds.contains(completedVmId)) {
                freeVmIds.remove(completedVmId);
            }

            freeVmIds.add(completedVmId);

            JobArrival completedArrival = null;
            for (JobArrival arrival : jobArrivals) {
                if (arrival.data.cloudletId == completed.getCloudletId()) {
                    completedArrival = arrival;
                    break;
                }
            }

            if (completedArrival == null) {
                throw new IllegalStateException(
                        "No workload record for Cloudlet " + completed.getCloudletId()
                );
            }

            double finishTime = CloudSim.clock() - simulationOriginTime;
            completedArrival.data.startTime =
                    completed.getExecStartTime() - simulationOriginTime;
            completedArrival.data.finishTime = finishTime;
            completedArrival.data.completed = true;
            completedArrival.data.waitingTime =
                    completedArrival.data.startTime - completedArrival.data.arrivalTime;
            completedArrival.data.lateness = Math.max(0.0,
                    finishTime - completedArrival.data.deadline);
            completedArrival.data.onTime = completedArrival.data.lateness <= 1e-9;
            finalJobCompletionTime = Math.max(finalJobCompletionTime, finishTime);

            WorkerStat workerStat = workerStats.get(completedVmId);
            if (workerStat != null) {
                double busy = finishTime - completedArrival.data.startTime;
                workerStat.workerSeconds += busy;
                workerStat.jobCount += 1;
            }

            System.out.printf(
                    "%nJOB COMPLETED: %s | VM %d | start %.6f | finish %.6f | deadline %.6f | waiting %.6f | lateness %.6f | %s%n",
                    completedArrival.data.jobId,
                    completedVmId,
                    completedArrival.data.startTime,
                    finishTime,
                    completedArrival.data.deadline,
                    completedArrival.data.waitingTime,
                    completedArrival.data.lateness,
                    completedArrival.data.onTime ? "ON TIME" : "LATE"
            );

            updateMaxLiveWorkersObserved();
            dispatchAvailableJobs();
            finishIfAllJobsCompleted();
        }

        private void finishIfAllJobsCompleted() {
            if (arrivalsProcessed == jobArrivals.size()
                    && cloudletsSubmitted == 0
                    && waitingJobs.isEmpty()
                    && getCloudletList().isEmpty()) {

                System.out.printf(
                        "%nAll jobs completed at workload time %.6f seconds.%n",
                        finalJobCompletionTime
                );

                clearDatacenters();
                finishExecution();
            }
        }

        private static class PendingJob {
            final JobData job;
            final double remaining;

            PendingJob(JobData job, double remaining) {
                this.job = job;
                this.remaining = remaining;
            }
        }
    }

    private enum ResearchEventTag implements CloudSimTags {
        JOB_ARRIVAL,
        VM_READY
    }

    private static void printResults(
            Workload workload,
            PredictiveBroker broker,
            double simulationFinishTime
    ) {
        System.out.println(
                "\n========== PREDICTIVE CC_v3.2 / CLOUDSIM RESULTS =========="
        );

        System.out.println("Input workload hash: " + workload.inputHash);
        System.out.println("Total jobs: " + workload.jobs.size());

        int onTime = 0;
        int late = 0;
        int unfinished = 0;

        System.out.println(
                "\nJob ID | Arrival | Runtime | Deadline | Start | Finish | Wait | Lateness | Result"
        );

        for (JobData job : workload.jobs) {
            String result;
            if (!job.completed) {
                result = "NOT COMPLETED";
                unfinished++;
            } else if (job.onTime) {
                result = "ON TIME";
                onTime++;
            } else {
                result = "LATE";
                late++;
            }

            System.out.printf(
                    "%s | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f | %s%n",
                    job.jobId,
                    job.arrivalTime,
                    job.estimatedRuntime,
                    job.deadline,
                    job.completed ? job.startTime : Double.NaN,
                    job.completed ? job.finishTime : Double.NaN,
                    job.completed ? job.waitingTime : Double.NaN,
                    job.completed ? job.lateness : Double.NaN,
                    result
            );
        }

        int completed = workload.jobs.size() - unfinished;

        System.out.println("\n========== SUMMARY ==========");
        System.out.println("Predictive mode: " + broker.mode);
        System.out.println("Completed jobs: " + completed);
        System.out.println("On-time jobs: " + onTime);
        System.out.println("Late jobs: " + late);
        System.out.println("Unfinished jobs: " + unfinished);

        double firstArrival = workload.jobs.stream()
                .mapToDouble(job -> job.arrivalTime)
                .min()
                .orElse(Double.NaN);

        double finalCompletion = broker.getFinalJobCompletionTime();
        System.out.printf(
                "Final workload completion time: %.9f seconds%n",
                completed == workload.jobs.size() ? finalCompletion : Double.NaN
        );

        System.out.printf(
                "Makespan: %.9f seconds%n",
                completed == workload.jobs.size() ? finalCompletion - firstArrival : Double.NaN
        );

        System.out.printf(
                "CloudSim simulation finish time: %.9f seconds%n",
                simulationFinishTime
        );

        System.out.println("\nExecution model: one job at a time per worker");
        System.out.println("Scheduling policy: EDF");
        System.out.println("Initial worker count: " + broker.getWorkerCount());
        System.out.println("Maximum worker count: " + broker.getMaxWorkers());
        System.out.println("Ready worker count at finish: " + broker.getReadyWorkerCount());
        System.out.println("Dynamic VM requests: " + broker.getDynamicVmRequestCount());
        System.out.println("Failed dynamic VM creations: " + broker.getFailedWorkerCount());
        System.out.println("Maximum total live workers observed: " + broker.maxLiveWorkersObserved);
        System.out.println("Total worker-seconds: " + broker.getTotalWorkerSeconds());
        System.out.println("Total startup delay: " + broker.getStartupDelaySeconds());
        System.out.println("Estimated cost model: worker_seconds * 0.04 / 3600.0");
        System.out.println("Estimated cost: " + broker.getEstimatedCost());
    }

    private static void writeResults(
            Workload workload,
            PredictiveBroker broker,
            double simulationFinishTime,
            Path outputPath
    ) throws IOException {
        int completed = 0;
        int onTime = 0;
        int late = 0;
        double firstArrival = Double.POSITIVE_INFINITY;

        for (JobData job : workload.jobs) {
            firstArrival = Math.min(firstArrival, job.arrivalTime);
            if (job.completed) {
                completed++;
                if (job.onTime) {
                    onTime++;
                } else {
                    late++;
                }
            }
        }

        int unfinished = workload.jobs.size() - completed;
        double finalCompletion = broker.getFinalJobCompletionTime();
        double makespan = unfinished == 0 ? finalCompletion - firstArrival : Double.NaN;

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schema_version\": \"cloudsim.results.v1\",\n");
        json.append("  \"input_workload_hash\": \"")
                .append(jsonEscape(workload.inputHash)).append("\",\n");
        json.append("  \"workload_source\": \"")
                .append(jsonEscape(workload.source)).append("\",\n");
        json.append("  \"seed\": ").append(workload.seed).append(",\n");
        json.append("  \"deadline_tier\": \"")
                .append(jsonEscape(workload.deadlineTier)).append("\",\n");
        json.append("  \"predictive_mode\": \"")
                .append(broker.mode.name()).append("\",\n");
        json.append("  \"configuration\": {\n");
        json.append("    \"scheduling_policy\": \"EDF\",\n");
        json.append("    \"execution_model\": \"one_job_at_a_time_per_worker\",\n");
        json.append("    \"worker_count\": ").append(broker.getWorkerCount()).append(",\n");
        json.append("    \"initial_worker_count\": ").append(broker.getWorkerCount()).append(",\n");
        json.append("    \"max_worker_count\": ").append(broker.getMaxWorkers()).append(",\n");
        json.append("    \"ready_worker_count_at_finish\": ").append(broker.getReadyWorkerCount()).append(",\n");
        json.append("    \"actual_ready_workers\": ").append(broker.getReadyWorkerCount()).append(",\n");
        json.append("    \"actual_provisioning_workers\": ").append(broker.requestedVmIds.size()).append(",\n");
        json.append("    \"max_total_live_workers_observed\": ").append(broker.maxLiveWorkersObserved).append(",\n");
        json.append("    \"dynamic_vm_requests\": ").append(broker.getDynamicVmRequestCount()).append(",\n");
        json.append("    \"failed_dynamic_vm_creations\": ").append(broker.getFailedWorkerCount()).append(",\n");
        json.append("    \"startup_delay_seconds\": ").append(broker.startupDelay).append(",\n");
        json.append("    \"vm_mips_per_pe\": ").append(VM_MIPS).append(",\n");
        json.append("    \"vm_pes\": ").append(VM_PES).append(",\n");
        json.append("    \"vm_ram_mb\": ").append(VM_RAM).append(",\n");
        json.append("    \"worker_seconds_total\": ").append(broker.getTotalWorkerSeconds()).append(",\n");
        json.append("    \"startup_delay_total_seconds\": ").append(broker.getStartupDelaySeconds()).append(",\n");
        json.append("    \"cost_model\": \"worker_seconds * 0.04 / 3600.0\",\n");
        json.append("    \"estimated_cost\": ").append(broker.getEstimatedCost()).append(",\n");
        json.append("    \"scale_out_decisions\": [");
        for (int i = 0; i < broker.scaleDecisionReasons.size(); i++) {
            json.append("\"").append(jsonEscape(broker.scaleDecisionReasons.get(i))).append("\"");
            if (i + 1 < broker.scaleDecisionReasons.size()) {
                json.append(", ");
            }
        }
        json.append("]\n");
        json.append("  },\n");

        json.append("  \"summary\": {\n");
        json.append("    \"total_jobs\": ").append(workload.jobs.size()).append(",\n");
        json.append("    \"completed_jobs\": ").append(completed).append(",\n");
        json.append("    \"on_time_jobs\": ").append(onTime).append(",\n");
        json.append("    \"late_jobs\": ").append(late).append(",\n");
        json.append("    \"unfinished_jobs\": ").append(unfinished).append(",\n");
        json.append("    \"first_arrival_time\": ");
        appendJsonNumber(json, firstArrival);
        json.append(",\n");
        json.append("    \"final_completion_time\": ");
        appendJsonNumber(json, unfinished == 0 ? finalCompletion : Double.NaN);
        json.append(",\n");
        json.append("    \"makespan\": ");
        appendJsonNumber(json, makespan);
        json.append(",\n");
        json.append("    \"cloudsim_simulation_finish_time\": ");
        appendJsonNumber(json, simulationFinishTime);
        json.append("\n  },\n");

        json.append("  \"jobs\": [\n");
        for (int i = 0; i < workload.jobs.size(); i++) {
            JobData job = workload.jobs.get(i);
            json.append("    {\n");
            json.append("      \"job_id\": \"").append(jsonEscape(job.jobId)).append("\",\n");
            json.append("      \"job_type\": \"").append(jsonEscape(job.jobType)).append("\",\n");
            json.append("      \"arrival_time\": "); appendJsonNumber(json, job.arrivalTime); json.append(",\n");
            json.append("      \"estimated_runtime\": "); appendJsonNumber(json, job.estimatedRuntime); json.append(",\n");
            json.append("      \"deadline\": "); appendJsonNumber(json, job.deadline); json.append(",\n");
            json.append("      \"cpu_required\": "); appendJsonNumber(json, job.cpuRequired); json.append(",\n");
            json.append("      \"memory_required\": ").append(job.memoryRequired).append(",\n");
            json.append("      \"priority\": ").append(job.priority).append(",\n");
            json.append("      \"completed\": ").append(job.completed).append(",\n");
            json.append("      \"start_time\": "); appendJsonNumber(json, job.completed ? job.startTime : Double.NaN); json.append(",\n");
            json.append("      \"finish_time\": "); appendJsonNumber(json, job.completed ? job.finishTime : Double.NaN); json.append(",\n");
            json.append("      \"waiting_time\": "); appendJsonNumber(json, job.completed ? job.waitingTime : Double.NaN); json.append(",\n");
            json.append("      \"lateness\": "); appendJsonNumber(json, job.completed ? job.lateness : Double.NaN); json.append(",\n");
            json.append("      \"on_time\": ");
            json.append(job.completed ? Boolean.toString(job.onTime) : "null");
            json.append("\n    }");
            if (i < workload.jobs.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ],\n");

        json.append("  \"workers\": [\n");
        List<Integer> vmIds = new ArrayList<>(broker.workerStats.keySet());
        vmIds.sort(Integer::compareTo);
        for (int i = 0; i < vmIds.size(); i++) {
            int vmId = vmIds.get(i);
            WorkerStat stat = broker.workerStats.get(vmId);
            json.append("    {\n");
            json.append("      \"vm_id\": ").append(vmId).append(",\n");
            json.append("      \"requested_at\": "); appendJsonNumber(json, stat.requestedAt); json.append(",\n");
            json.append("      \"ready_at\": "); appendJsonNumber(json, stat.readyAt); json.append(",\n");
            json.append("      \"worker_seconds\": "); appendJsonNumber(json, stat.workerSeconds); json.append(",\n");
            json.append("      \"job_count\": ").append(stat.jobCount).append("\n");
            json.append("    }");
            if (i < vmIds.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");

        Path absoluteOutput = outputPath.toAbsolutePath();
        Path parent = absoluteOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(absoluteOutput, json.toString());
        System.out.println("\nJSON RESULTS EXPORTED:");
        System.out.println(absoluteOutput);
    }

    private static void appendJsonNumber(StringBuilder json, double value) {
        if (Double.isFinite(value)) {
            json.append(Double.toString(value));
        } else {
            json.append("null");
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                    break;
            }
        }
        return escaped.toString();
    }
}
