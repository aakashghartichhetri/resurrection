package dev.resurrection.domain;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A narrow, deterministic microbenchmark, not a claim about HTTP or database throughput. */
public final class ReplayBenchmark {
    private static final UUID ID = UUID.fromString("4e100fa0-2c5d-4ddd-b7a8-ff0211ce8891");

    public static void main(String[] args) {
        System.out.printf("JVM=%s OS=%s CPUs=%d%n", System.getProperty("java.version"),
                System.getProperty("os.name"), Runtime.getRuntime().availableProcessors());
        System.out.println("events,median_ms,p95_ms,replays_per_second,avg_thread_alloc_bytes");
        for (int size : new int[]{10, 100, 1000, 5000}) run(size);
    }

    private static void run(int size) {
        List<ReplayEngine.Event> events = new ArrayList<>(size);
        List<ReplayEngine.Checkpoint> checkpoints = new ArrayList<>(size / 2);
        String previous = ReplayEngine.GENESIS;
        WorkflowState state = null;
        EventType[] cycle = {EventType.FAULT_ARMED, EventType.STEP_FAILED, EventType.RECOVERED};
        for (int n = 1; n <= size; n++) {
            EventType type = n == 1 ? EventType.CREATED : cycle[(n - 2) % 3];
            var unsigned = new ReplayEngine.Event(ID, n, type, "benchmark", previous, "");
            var event = new ReplayEngine.Event(ID, n, type, "benchmark", previous,
                    ReplayEngine.hashEvent(unsigned));
            events.add(event);
            state = ReplayEngine.apply(state, type);
            previous = event.hash();
            if (n % 2 == 0) {
                var candidate = new ReplayEngine.Checkpoint(n, state.step(), state.status(), previous, "");
                checkpoints.add(new ReplayEngine.Checkpoint(n, state.step(), state.status(), previous,
                        ReplayEngine.checkpointChecksum(ID, candidate)));
            }
        }
        for (int i = 0; i < 35; i++) ReplayEngine.replay(ID, events, checkpoints);
        var allocationBean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean threadAlloc = allocationBean instanceof com.sun.management.ThreadMXBean memory
                && memory.isThreadAllocatedMemorySupported() ? memory : null;
        if (threadAlloc != null && !threadAlloc.isThreadAllocatedMemoryEnabled())
            threadAlloc.setThreadAllocatedMemoryEnabled(true);
        int count = 75;
        long[] durations = new long[count];
        long allocations = 0;
        long threadId = Thread.currentThread().threadId();
        for (int i = 0; i < count; i++) {
            long beforeAlloc = threadAlloc == null ? 0 : threadAlloc.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();
            ReplayEngine.replay(ID, events, checkpoints);
            durations[i] = System.nanoTime() - start;
            if (threadAlloc != null) allocations += threadAlloc.getThreadAllocatedBytes(threadId) - beforeAlloc;
        }
        Arrays.sort(durations);
        double median = durations[count / 2] / 1_000_000d;
        double p95 = durations[(int) Math.ceil(count * 0.95) - 1] / 1_000_000d;
        double totalSeconds = Arrays.stream(durations).sum() / 1_000_000_000d;
        System.out.printf(Locale.ROOT, "%d,%.3f,%.3f,%.0f,%s%n", size, median, p95,
                count / totalSeconds, threadAlloc == null ? "unavailable" : String.valueOf(allocations / count));
    }
}
