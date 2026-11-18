package research;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
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

public class CloudSimValidationDynamic {

    private static final String WORKLOAD_FILE =
            "data/ccv2_workload_seed42.json";

    // Microsecond-level Cloudlet length resolution.
    private static final int VM_MIPS = 1_000_000;
    private static final int VM_PES = 2;
    private static final int VM_RAM = 4096;
    private static final long VM_BW = 10000;
    private static final long VM_STORAGE = 100000;

    
    public static void main(String[] args) {

        try {
            Path workloadPath = args.length >= 1
                    ? Path.of(args[0])
                    : Path.of(WORKLOAD_FILE);

            Path outputPath = args.length >= 2
                    ? Path.of(args[1])
                    : Path.of("data/cloudsim_edf_results.json");

            int workerCount = args.length >= 3
                    ? Integer.parseInt(args[2])
                    : 1;

            int maxWorkers = args.length >= 4
                    ? Integer.parseInt(args[3])
                    : 4;

            double startupDelay = args.length >= 5
                    ? Double.parseDouble(args[4])
                    : 10.0;

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

            System.out.println("Initial workers: " + workerCount);
            System.out.println("Maximum workers: " + maxWorkers);
            System.out.println("Configured startup delay: "
                    + startupDelay + " seconds");

            System.out.println("Loading CC_v3.2 exported workload...");
            System.out.println("Workload path: " + workloadPath);

            Workload workload = loadWorkload(workloadPath);

            System.out.println("Workload loaded successfully.");
            System.out.println("Schema: " + workload.schemaVersion);
            System.out.println("Source: " + workload.source);
            System.out.println("Seed: " + workload.seed);
            System.out.println("Deadline tier: " + workload.deadlineTier);
            System.out.println("Jobs: " + workload.jobs.size());
            System.out.println("Input hash: " + workload.inputHash);

            Log.println("Starting CloudSim workload integration...");

            CloudSim.init(1, Calendar.getInstance(), false);

            createDatacenter("Research_Datacenter", maxWorkers);

            ArrivalAwareBroker broker =
                    new ArrivalAwareBroker("Research_Broker", workerCount, maxWorkers, startupDelay);

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

            UtilizationModel utilization =
                    new UtilizationModelFull();

            int cloudletId = 0;

            for (JobData data : workload.jobs) {

                long cloudletLength =
                        Math.max(1, Math.round(
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

            writeResults(
                    workload,
                    broker,
                    simulationFinishTime,
                    outputPath
            );

        } catch (Exception exception) {
            System.err.println("CLOUDSIM VALIDATION FAILED:");
            exception.printStackTrace();
            System.exit(1);
        }
    }

    // ---------------------------------------------------------
    // Workload JSON reader
    // ---------------------------------------------------------

    private static Workload loadWorkload(Path path)
            throws IOException {

        String json = Files.readString(path);

        Workload workload = new Workload();

        workload.schemaVersion =
                stringField(json, "schema_version");

        workload.source =
                stringField(json, "source");

        workload.seed =
                (int) numberField(json, "seed");

        workload.deadlineTier =
                stringField(json, "deadline_tier");

        workload.inputHash =
                stringField(json, "input_hash");

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

    // ---------------------------------------------------------
    // Arrival-aware, EDF broker
    // ---------------------------------------------------------

    private static class ArrivalAwareBroker extends DatacenterBroker {

        private List<JobArrival> jobArrivals = new ArrayList<>();

        private final PriorityQueue<JobArrival> waitingJobs =
                new PriorityQueue<>(
                        Comparator.comparingDouble(
                                (JobArrival a) -> a.data.deadline
                        ).thenComparingDouble(
                                a -> a.arrivalOffset
                        ).thenComparing(
                                a -> a.data.jobId
                        )
                );

        private final PriorityQueue<Integer> freeVmIds =
                new PriorityQueue<>();

        private final Set<Integer> readyVmIds = new HashSet<>();
        private final Set<Integer> requestedVmIds = new HashSet<>();
        private final Set<Integer> failedVmIds = new HashSet<>();

        private final int workerCount;
        private final int maxWorkers;
        private final double startupDelay;

        private int nextVmIdToCreate;
        private int dynamicVmRequestCount = 0;
        private int arrivalsProcessed = 0;

        private boolean arrivalEventsScheduled = false;
        private boolean scaleOutRequestPending = false;
        private boolean scaleOutFailed = false;

        private double simulationOriginTime = 0.0;
        private double finalJobCompletionTime = 0.0;

        public ArrivalAwareBroker(
                String name,
                int workerCount,
                int maxWorkers,
                double startupDelay
        ) throws Exception {
            super(name);

            this.workerCount = workerCount;
            this.maxWorkers = maxWorkers;
            this.startupDelay = startupDelay;
            this.nextVmIdToCreate = workerCount;

            for (int vmId = 0; vmId < workerCount; vmId++) {
                freeVmIds.add(vmId);
                readyVmIds.add(vmId);
            }
        }

        public void setJobArrivals(List<JobArrival> arrivals) {
            this.jobArrivals = arrivals;
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
                scaleOutRequestPending = false;
                scaleOutFailed = true;
                failedVmIds.add(vmId);

                System.out.printf(
                        "%nDYNAMIC VM CREATION FAILED: VM %d%n",
                        vmId
                );

                finishIfAllJobsCompleted();
                return;
            }

            System.out.printf(
                    "%nDYNAMIC VM CREATED: VM %d | startup delay %.6f seconds%n",
                    vmId,
                    startupDelay
            );

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

                System.out.printf(
                        "%nJOB ARRIVAL: %s at workload time %.6f%n",
                        arrival.data.jobId,
                        CloudSim.clock() - simulationOriginTime
                );

                dispatchAvailableJobs();

            } else if (event.getTag() == ResearchEventTag.VM_READY) {

                int vmId = (Integer) event.getData();

                if (!requestedVmIds.contains(vmId)) {
                    throw new IllegalStateException(
                            "VM_READY received for an unrequested VM: " + vmId
                    );
                }

                if (!readyVmIds.add(vmId)) {
                    throw new IllegalStateException(
                            "VM became ready more than once: " + vmId
                    );
                }

                freeVmIds.add(vmId);
                scaleOutRequestPending = false;

                System.out.printf(
                        "%nDYNAMIC VM READY: VM %d | workload time %.6f | ready workers %d%n",
                        vmId,
                        CloudSim.clock() - simulationOriginTime,
                        readyVmIds.size()
                );

                dispatchAvailableJobs();
                finishIfAllJobsCompleted();

            } else {
                super.processOtherEvent(event);
            }
        }

        private void dispatchAvailableJobs() {

            while (!waitingJobs.isEmpty() && !freeVmIds.isEmpty()) {

                JobArrival next = waitingJobs.poll();
                int vmId = freeVmIds.poll();

                next.cloudlet.setGuestId(vmId);
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
                if (shouldScaleOutForDeadlineRisk()) {
                    requestScaleOut();
                } else if (freeVmIds.isEmpty() && !scaleOutRequestPending) {
                    requestScaleOut();
                }
            }
        }

        private boolean shouldScaleOutForDeadlineRisk() {

            if (waitingJobs.isEmpty()
                    || scaleOutFailed
                    || scaleOutRequestPending
                    || nextVmIdToCreate >= maxWorkers) {
                return false;
            }

            double now = CloudSim.clock() - simulationOriginTime;
            int readyWorkers = readyVmIds.size();
            if (readyWorkers <= 0) {
                return true;
            }

            double queuedRuntime = 0.0;
            double earliestDeadline = Double.POSITIVE_INFINITY;

            for (JobArrival arrival : waitingJobs) {
                queuedRuntime += arrival.data.estimatedRuntime;
                earliestDeadline = Math.min(earliestDeadline, arrival.data.deadline);
            }

            double projectedQueueTime = queuedRuntime / Math.max(1, readyWorkers);
            double slackForEarliest = earliestDeadline - now;

            if (slackForEarliest <= startupDelay + projectedQueueTime + 1.0) {
                return true;
            }

            JobArrival earliest = waitingJobs.peek();
            if (earliest != null) {
                double earliestArrivalSlack = earliest.data.deadline - now;
                if (earliestArrivalSlack <= startupDelay + earliest.data.estimatedRuntime) {
                    return true;
                }
            }

            return false;
        }

        private void requestScaleOut() {

            if (scaleOutFailed
                    || scaleOutRequestPending
                    || waitingJobs.isEmpty()
                    || !freeVmIds.isEmpty()) {
                return;
            }

            if (nextVmIdToCreate >= maxWorkers) {
                return;
            }

            if (getDatacenterIdsList().isEmpty()) {
                scaleOutFailed = true;

                System.out.println(
                        "DYNAMIC SCALE-OUT FAILED: no datacenter is available"
                );
                return;
            }

            int vmId = nextVmIdToCreate++;

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
            scaleOutRequestPending = true;
            dynamicVmRequestCount++;

            System.out.printf(
                    "%nSCALE-OUT REQUEST: VM %d | current ready workers %d | maximum workers %d | workload time %.6f%n",
                    vmId,
                    readyVmIds.size(),
                    maxWorkers,
                    CloudSim.clock() - simulationOriginTime
            );

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

            if (completedVmId < 0
                    || completedVmId >= maxWorkers
                    || !readyVmIds.contains(completedVmId)) {
                throw new IllegalStateException(
                        "Completed job came from an invalid or unready VM: "
                                + completedVmId
                );
            }

            if (freeVmIds.contains(completedVmId)) {
                throw new IllegalStateException(
                        "VM " + completedVmId + " was already marked free"
                );
            }

            freeVmIds.add(completedVmId);

            JobArrival completedArrival = null;

            for (JobArrival arrival : jobArrivals) {
                if (arrival.data.cloudletId == completed.getCloudletId()) {
                    completedArrival = arrival;
                    break;
                }
            }

            double finishTime =
                    CloudSim.clock() - simulationOriginTime;

            if (completedArrival == null) {
                throw new IllegalStateException(
                        "No workload record for Cloudlet "
                                + completed.getCloudletId()
                );
            }

            completedArrival.data.startTime =
                    completed.getExecStartTime() - simulationOriginTime;

            completedArrival.data.finishTime = finishTime;
            completedArrival.data.completed = true;

            completedArrival.data.onTime =
                    finishTime <= completedArrival.data.deadline;

            finalJobCompletionTime = Math.max(
                    finalJobCompletionTime,
                    finishTime
            );

            System.out.printf(
                    "%nJOB COMPLETED: %s | VM %d | start %.6f "
                            + "| finish %.6f | deadline %.6f | %s%n",
                    completedArrival.data.jobId,
                    completedVmId,
                    completedArrival.data.startTime,
                    finishTime,
                    completedArrival.data.deadline,
                    completedArrival.data.onTime ? "ON TIME" : "LATE"
            );

            dispatchAvailableJobs();
            finishIfAllJobsCompleted();
        }

        private void finishIfAllJobsCompleted() {

            if (arrivalsProcessed == jobArrivals.size()
                    && cloudletsSubmitted == 0
                    && waitingJobs.isEmpty()
                    && getCloudletList().isEmpty()
                    && !scaleOutRequestPending) {

                System.out.printf(
                        "%nAll jobs completed at workload time %.6f seconds.%n",
                        finalJobCompletionTime
                );

                clearDatacenters();
                finishExecution();
            }
        }
    }

    // ---------------------------------------------------------
    // Workload and job data
    // ---------------------------------------------------------

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
    }

    private static class JobArrival {

        final JobData data;
        final Cloudlet cloudlet;
        final double arrivalOffset;

        JobArrival(
                JobData data,
                Cloudlet cloudlet,
                double arrivalOffset
        ) {
            this.data = data;
            this.cloudlet = cloudlet;
            this.arrivalOffset = arrivalOffset;
        }
    }

    private enum ResearchEventTag implements CloudSimTags {
        JOB_ARRIVAL,
        VM_READY
    }

    // ---------------------------------------------------------
    // Result reporting
    // ---------------------------------------------------------

    
    private static void printResults(
            Workload workload,
            ArrivalAwareBroker broker,
            double simulationFinishTime
    ) {

        System.out.println(
                "\n========== CC_v3.2 / CLOUDSIM RESULTS =========="
        );

        System.out.println("Input workload hash: " + workload.inputHash);
        System.out.println("Total jobs: " + workload.jobs.size());

        int onTime = 0;
        int late = 0;
        int unfinished = 0;

        System.out.println(
                "\nJob ID | Arrival | Runtime | Deadline | Start | Finish | Result"
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
                    "%s | %.6f | %.6f | %.6f | %.6f | %.6f | %s%n",
                    job.jobId,
                    job.arrivalTime,
                    job.estimatedRuntime,
                    job.deadline,
                    job.completed ? job.startTime : Double.NaN,
                    job.completed ? job.finishTime : Double.NaN,
                    result
            );
        }

        int completed = workload.jobs.size() - unfinished;

        System.out.println("\n========== SUMMARY ==========");
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
                completed == workload.jobs.size()
                        ? finalCompletion : Double.NaN
        );

        System.out.printf(
                "Makespan: %.9f seconds%n",
                completed == workload.jobs.size()
                        ? finalCompletion - firstArrival : Double.NaN
        );

        System.out.printf(
                "CloudSim simulation finish time: %.9f seconds%n",
                simulationFinishTime
        );

        System.out.println(
                "\nExecution model: one job at a time per worker"
        );
        System.out.println("Scheduling policy: EDF");
        System.out.println("Initial worker count: " + broker.getWorkerCount());
        System.out.println("Maximum worker count: " + broker.getMaxWorkers());
        System.out.println("Ready worker count at finish: " + broker.getReadyWorkerCount());
        System.out.println("Dynamic VM requests: " + broker.getDynamicVmRequestCount());
        System.out.println("Failed dynamic VM creations: " + broker.getFailedWorkerCount());
        System.out.println(
                "VM configuration: " + VM_PES + " PEs, "
                        + VM_MIPS + " MIPS per PE"
        );
    }


    // ---------------------------------------------------------
    // Structured JSON result export
    // ---------------------------------------------------------

    private static void writeResults(
            Workload workload,
            ArrivalAwareBroker broker,
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

        double makespan = unfinished == 0
                ? finalCompletion - firstArrival
                : Double.NaN;

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

        json.append("  \"configuration\": {\n");
        json.append("    \"scheduling_policy\": \"EDF\",\n");
        json.append("    \"execution_model\": \"one_job_at_a_time_per_worker\",\n");
        json.append("    \"worker_count\": ").append(broker.getWorkerCount()).append(",\n");
        json.append("    \"initial_worker_count\": ").append(broker.getWorkerCount()).append(",\n");
        json.append("    \"max_worker_count\": ").append(broker.getMaxWorkers()).append(",\n");
        json.append("    \"ready_worker_count_at_finish\": ").append(broker.getReadyWorkerCount()).append(",\n");
        json.append("    \"dynamic_vm_requests\": ").append(broker.getDynamicVmRequestCount()).append(",\n");
        json.append("    \"failed_dynamic_vm_creations\": ").append(broker.getFailedWorkerCount()).append(",\n");
        json.append("    \"startup_delay_seconds\": ").append(broker.startupDelay).append(",\n");
        json.append("    \"vm_mips_per_pe\": ").append(VM_MIPS).append(",\n");
        json.append("    \"vm_pes\": ").append(VM_PES).append(",\n");
        json.append("    \"vm_ram_mb\": ").append(VM_RAM).append("\n");
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
        appendJsonNumber(json, unfinished == 0
                ? finalCompletion : Double.NaN);
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
            json.append("      \"job_id\": \"")
                    .append(jsonEscape(job.jobId)).append("\",\n");
            json.append("      \"job_type\": \"")
                    .append(jsonEscape(job.jobType)).append("\",\n");

            json.append("      \"arrival_time\": ");
            appendJsonNumber(json, job.arrivalTime);
            json.append(",\n");

            json.append("      \"estimated_runtime\": ");
            appendJsonNumber(json, job.estimatedRuntime);
            json.append(",\n");

            json.append("      \"deadline\": ");
            appendJsonNumber(json, job.deadline);
            json.append(",\n");

            json.append("      \"cpu_required\": ");
            appendJsonNumber(json, job.cpuRequired);
            json.append(",\n");

            json.append("      \"memory_required\": ")
                    .append(job.memoryRequired).append(",\n");

            json.append("      \"priority\": ")
                    .append(job.priority).append(",\n");

            json.append("      \"completed\": ")
                    .append(job.completed).append(",\n");

            json.append("      \"start_time\": ");
            appendJsonNumber(json, job.completed
                    ? job.startTime : Double.NaN);
            json.append(",\n");

            json.append("      \"finish_time\": ");
            appendJsonNumber(json, job.completed
                    ? job.finishTime : Double.NaN);
            json.append(",\n");

            json.append("      \"on_time\": ");
            json.append(job.completed
                    ? Boolean.toString(job.onTime) : "null");
            json.append("\n    }");

            if (i < workload.jobs.size() - 1) {
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
        System.out.println("Result schema: cloudsim.results.v1");
    }

    private static void appendJsonNumber(
            StringBuilder json,
            double value
    ) {
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
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }

        return escaped.toString();
    }

    // ---------------------------------------------------------
    // Datacenter creation
    // ---------------------------------------------------------

    private static Datacenter createDatacenter(
            String name,
            int workerCount
    ) throws Exception {

        List<Host> hostList = new ArrayList<>();
        List<Pe> peList = new ArrayList<>();

        for (int i = 0; i < workerCount * VM_PES; i++) {
            peList.add(
                    new Pe(i, new PeProvisionerSimple(VM_MIPS))
            );
        }

        Host host = new Host(
                0,
                new RamProvisionerSimple(workerCount * VM_RAM),
                new BwProvisionerSimple(workerCount * VM_BW),
                workerCount * VM_STORAGE,
                peList,
                new VmSchedulerTimeShared(peList)
        );

        hostList.add(host);

        DatacenterCharacteristics characteristics =
                new DatacenterCharacteristics(
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
}

