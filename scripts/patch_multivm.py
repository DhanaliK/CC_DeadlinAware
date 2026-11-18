from pathlib import Path

path = Path("src/main/java/research/CloudSimValidation.java")
source = path.read_text(encoding="utf-8")
original = source

def replace_once(old, new, label):
    global source
    count = source.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly 1 match, found {count}. "
            "No file was written."
        )
    source = source.replace(old, new, 1)
    print(f"PATCHED: {label}")

# 1. Add worker-count argument.
replace_once(
'''            Path outputPath = args.length >= 2
                    ? Path.of(args[1])
                    : Path.of("data/cloudsim_edf_results.json");
''',
'''            Path outputPath = args.length >= 2
                    ? Path.of(args[1])
                    : Path.of("data/cloudsim_edf_results.json");

            int workerCount = args.length >= 3
                    ? Integer.parseInt(args[2])
                    : 1;

            if (workerCount < 1 || workerCount > 16) {
                throw new IllegalArgumentException(
                        "workerCount must be between 1 and 16"
                );
            }

            System.out.println("Configured workers: " + workerCount);
''',
"worker-count argument"
)

# 2. Pass worker count to datacenter and broker.
replace_once(
'''            createDatacenter("Research_Datacenter");

            ArrivalAwareBroker broker =
                    new ArrivalAwareBroker("Research_Broker");
''',
'''            createDatacenter("Research_Datacenter", workerCount);

            ArrivalAwareBroker broker =
                    new ArrivalAwareBroker("Research_Broker", workerCount);
''',
"datacenter and broker construction"
)

# 3. Create the requested number of VMs.
replace_once(
'''            List<Vm> vmList = new ArrayList<>();

            Vm vm = new Vm(
                    0,
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
            broker.submitGuestList(vmList);
''',
'''            List<Vm> vmList = new ArrayList<>();

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
''',
"multiple VM creation"
)

# 4. Remove the hard-coded VM assignment.
replace_once(
'''                cloudlet.setUserId(brokerId);
                cloudlet.setGuestId(0);
''',
'''                cloudlet.setUserId(brokerId);
''',
"deferred Cloudlet-to-VM assignment"
)

# 5. Replace the broker implementation.
start_marker = "    private static class ArrivalAwareBroker extends DatacenterBroker {"
end_marker = "    // ---------------------------------------------------------\n    // Workload and job data"

start = source.find(start_marker)
end = source.find(end_marker, start)

if start < 0 or end < 0 or end <= start:
    raise RuntimeError("Could not locate the complete broker class. No file was written.")

new_broker = '''    private static class ArrivalAwareBroker extends DatacenterBroker {

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

        // Available VM IDs, selected in ascending order.
        private final PriorityQueue<Integer> freeVmIds =
                new PriorityQueue<>();

        private final int workerCount;
        private int arrivalsProcessed = 0;
        private boolean arrivalEventsScheduled = false;
        private double simulationOriginTime = 0.0;
        private double finalJobCompletionTime = 0.0;

        public ArrivalAwareBroker(String name, int workerCount)
                throws Exception {
            super(name);

            this.workerCount = workerCount;

            for (int vmId = 0; vmId < workerCount; vmId++) {
                freeVmIds.add(vmId);
            }
        }

        public void setJobArrivals(List<JobArrival> arrivals) {
            this.jobArrivals = arrivals;
        }

        public int getWorkerCount() {
            return workerCount;
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

                // Only this one newly queued Cloudlet is submitted here.
                super.submitCloudlets();
            }
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

            // Balance the increment performed by super.submitCloudlets().
            cloudletsSubmitted--;

            int completedVmId = completed.getGuestId();

            if (completedVmId < 0 || completedVmId >= workerCount) {
                throw new IllegalStateException(
                        "Invalid completed VM ID: " + completedVmId
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

            if (arrivalsProcessed == jobArrivals.size()
                    && cloudletsSubmitted == 0
                    && waitingJobs.isEmpty()
                    && getCloudletList().isEmpty()) {

                finalJobCompletionTime = finishTime;

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

source = source[:start] + new_broker + source[end:]

# 6. Make host capacity sufficient for all VMs.
replace_once(
'''    private static Datacenter createDatacenter(String name)
            throws Exception {
''',
'''    private static Datacenter createDatacenter(
            String name,
            int workerCount
    ) throws Exception {
''',
"datacenter method signature"
)

replace_once(
'''        for (int i = 0; i < VM_PES; i++) {
''',
'''        for (int i = 0; i < workerCount * VM_PES; i++) {
''',
"host PE capacity"
)

replace_once(
'''                new RamProvisionerSimple(8192),
                new BwProvisionerSimple(100000),
                1000000,
''',
'''                new RamProvisionerSimple(workerCount * VM_RAM),
                new BwProvisionerSimple(workerCount * VM_BW),
                workerCount * VM_STORAGE,
''',
"host RAM, bandwidth and storage capacity"
)

# 7. Record worker count in human-readable output.
replace_once(
'''        System.out.println("Scheduling policy: EDF");
        System.out.println(
                "VM configuration: " + VM_PES + " PEs, "
''',
'''        System.out.println("Scheduling policy: EDF");
        System.out.println("Worker count: " + broker.getWorkerCount());
        System.out.println(
                "VM configuration: " + VM_PES + " PEs, "
''',
"printed worker count"
)

# JSON worker-count metadata will be added separately.

if source == original:
    raise RuntimeError("No changes were made.")

path.write_text(source, encoding="utf-8")
print("\\nSUCCESS: multi-VM patch written.")
