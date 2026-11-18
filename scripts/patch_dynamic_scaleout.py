from pathlib import Path

source = Path(r"src/main/java/research/CloudSimValidationDynamic.java")
text = source.read_text(encoding="utf-8-sig")

def replace_once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    text = text.replace(old, new, 1)
    print("PATCHED:", label)

replace_once(
    "import java.util.HashMap;\n",
    "import java.util.HashMap;\nimport java.util.HashSet;\n",
    "HashSet import"
)

replace_once(
    "import java.util.PriorityQueue;\n",
    "import java.util.PriorityQueue;\nimport java.util.Set;\n",
    "Set import"
)

start_marker = "    private static class ArrivalAwareBroker extends DatacenterBroker {"
end_marker = "    // ---------------------------------------------------------\n    // Workload and job data"

if text.count(start_marker) != 1 or text.count(end_marker) != 1:
    raise RuntimeError("Could not uniquely locate the broker implementation")

start = text.index(start_marker)
end = text.index(end_marker)

broker = r'''    private static class ArrivalAwareBroker extends DatacenterBroker {

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

            if (!waitingJobs.isEmpty() && freeVmIds.isEmpty()) {
                requestScaleOut();
            }
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

'''

text = text[:start] + broker + text[end:]

replace_once(
    "    private enum ResearchEventTag implements CloudSimTags {\n"
    "        JOB_ARRIVAL\n"
    "    }",
    "    private enum ResearchEventTag implements CloudSimTags {\n"
    "        JOB_ARRIVAL,\n"
    "        VM_READY\n"
    "    }",
    "VM_READY event tag"
)

replace_once(
    '        System.out.println("Worker count: " + broker.getWorkerCount());',
    '        System.out.println("Initial worker count: " + broker.getWorkerCount());\n'
    '        System.out.println("Maximum worker count: " + broker.getMaxWorkers());\n'
    '        System.out.println("Ready worker count at finish: " + broker.getReadyWorkerCount());\n'
    '        System.out.println("Dynamic VM requests: " + broker.getDynamicVmRequestCount());\n'
    '        System.out.println("Failed dynamic VM creations: " + broker.getFailedWorkerCount());',
    "console scale-out metrics"
)

replace_once(
    '        json.append("    \\"worker_count\\": ").append(broker.getWorkerCount()).append(",\\n");',
    '        json.append("    \\"worker_count\\": ").append(broker.getWorkerCount()).append(",\\n");\n'
    '        json.append("    \\"initial_worker_count\\": ").append(broker.getWorkerCount()).append(",\\n");\n'
    '        json.append("    \\"max_worker_count\\": ").append(broker.getMaxWorkers()).append(",\\n");\n'
    '        json.append("    \\"ready_worker_count_at_finish\\": ").append(broker.getReadyWorkerCount()).append(",\\n");\n'
    '        json.append("    \\"dynamic_vm_requests\\": ").append(broker.getDynamicVmRequestCount()).append(",\\n");\n'
    '        json.append("    \\"failed_dynamic_vm_creations\\": ").append(broker.getFailedWorkerCount()).append(",\\n");\n'
    '        json.append("    \\"startup_delay_seconds\\": ").append(broker.startupDelay).append(",\\n");',
    "JSON scale-out metrics"
)

# Preserve the project's established CRLF convention and write without a BOM.
text = text.replace("\r\n", "\n").replace("\n", "\r\n")
with source.open("w", encoding="utf-8", newline="") as output:
    output.write(text)

print("\nDynamic scale-out patch applied successfully.")