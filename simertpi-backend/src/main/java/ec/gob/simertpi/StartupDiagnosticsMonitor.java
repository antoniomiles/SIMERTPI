package ec.gob.simertpi;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in, bounded startup sampler. It deliberately has no Spring context dependency. */
final class StartupDiagnosticsMonitor {

    static final String PROPERTY = "simertpi.startup-diagnostics.enabled";
    static final String ENVIRONMENT_VARIABLE = "SIMERTPI_STARTUP_DIAGNOSTICS_ENABLED";
    static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);
    static final Duration MAX_DURATION = Duration.ofMinutes(18);
    private static final Logger log = LoggerFactory.getLogger(StartupDiagnosticsMonitor.class);
    private static final int MAX_STACK_FRAMES = 8;

    private final long startedNanos;
    private final long mainThreadId;
    private final long processCpuAtStart;
    private final Consumer<String> sink;
    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> future;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile Thread monitorThread;
    private long previousProcessCpuNanos;
    private CpuThrottle previousThrottle;
    private long previousGcCount;
    private long previousGcTime;

    static StartupDiagnosticsMonitor startIfEnabled() {
        String configured = System.getProperty(PROPERTY);
        if (configured == null) {
            configured = System.getenv(ENVIRONMENT_VARIABLE);
        }
        if (!Boolean.parseBoolean(configured)) {
            return disabled();
        }
        return new StartupDiagnosticsMonitor(Thread.currentThread(), SAMPLE_INTERVAL, MAX_DURATION,
                message -> log.info("{}", message), true);
    }

    private static StartupDiagnosticsMonitor disabled() {
        return new StartupDiagnosticsMonitor(Thread.currentThread(), SAMPLE_INTERVAL, MAX_DURATION,
                ignored -> { }, false);
    }

    StartupDiagnosticsMonitor(Thread mainThread, Duration interval, Duration maxDuration,
            Consumer<String> sink, boolean enabled) {
        this.mainThreadId = mainThread.getId();
        this.sink = sink;
        this.startedNanos = System.nanoTime();
        if (!enabled) {
            this.processCpuAtStart = -1;
            this.previousProcessCpuNanos = -1;
            this.previousThrottle = new CpuThrottle(-1, -1);
            this.previousGcCount = -1;
            this.previousGcTime = -1;
            executor = null;
            future = null;
            stopped.set(true);
            return;
        }
        this.processCpuAtStart = processCpuTimeNanos();
        this.previousProcessCpuNanos = processCpuAtStart;
        this.previousThrottle = CpuThrottle.read();
        this.previousGcCount = gcCount();
        this.previousGcTime = gcTimeMillis();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "simertpi-startup-diagnostics");
            thread.setDaemon(true);
            monitorThread = thread;
            return thread;
        };
        executor = Executors.newSingleThreadScheduledExecutor(factory);
        future = executor.scheduleAtFixedRate(this::sampleSafely, interval.toMillis(),
                interval.toMillis(), TimeUnit.MILLISECONDS);
        executor.schedule(() -> stop("max_duration"), maxDuration.toMillis(), TimeUnit.MILLISECONDS);
    }

    void stop(String reason) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        if (future != null) {
            future.cancel(false);
        }
        if (executor != null) {
            executor.shutdown();
        }
        sink.accept("SIMERTPI_STARTUP_DIAGNOSTIC stopped reason=" + reason);
    }

    boolean isStopped() {
        return stopped.get();
    }

    boolean monitorThreadIsDaemon() {
        return monitorThread == null || monitorThread.isDaemon();
    }

    private void sampleSafely() {
        if (stopped.get()) {
            return;
        }
        try {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            if (elapsedMs >= MAX_DURATION.toMillis()) {
                stop("max_duration");
                return;
            }
            ThreadInfo main = ManagementFactory.getThreadMXBean().getThreadInfo(mainThreadId, MAX_STACK_FRAMES);
            String state = main == null ? "TERMINATED" : main.getThreadState().name();
            String stack = summarize(main);
            long processCpu = processCpuTimeNanos();
            String processCpuMs = valueOrUnavailable(processCpu < 0 ? -1 : processCpu / 1_000_000);
            String processCpuDeltaMs = valueOrUnavailable(processCpu < 0 || previousProcessCpuNanos < 0
                    ? -1 : (processCpu - previousProcessCpuNanos) / 1_000_000);
            previousProcessCpuNanos = processCpu;
            Runtime runtime = Runtime.getRuntime();
            long heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
            long gcCount = gcCount();
            long gcTime = gcTimeMillis();
            CpuThrottle throttle = CpuThrottle.read();
            sink.accept("SIMERTPI_STARTUP_DIAGNOSTIC elapsedMs=" + elapsedMs
                    + " mainState=" + state
                    + " mainStack=" + stack
                    + " processCpuMs=" + processCpuMs
                    + " processCpuDeltaMs=" + processCpuDeltaMs
                    + " heapUsedBytes=" + heapUsedBytes
                    + " gcCount=" + valueOrUnavailable(gcCount)
                    + " gcCountDelta=" + deltaOrUnavailable(gcCount, previousGcCount)
                    + " gcTimeMs=" + valueOrUnavailable(gcTime)
                    + " gcTimeDeltaMs=" + deltaOrUnavailable(gcTime, previousGcTime)
                    + " cpuThrottledPeriods=" + valueOrUnavailable(throttle.throttledPeriods)
                    + " cpuThrottledPeriodsDelta=" + deltaOrUnavailable(throttle.throttledPeriods,
                            previousThrottle.throttledPeriods)
                    + " cpuThrottledUsec=" + valueOrUnavailable(throttle.throttledUsec)
                    + " cpuThrottledUsecDelta=" + deltaOrUnavailable(throttle.throttledUsec,
                            previousThrottle.throttledUsec));
            previousGcCount = gcCount;
            previousGcTime = gcTime;
            previousThrottle = throttle;
            if (elapsedMs >= MAX_DURATION.toMillis()) {
                stop("max_duration");
            }
        } catch (RuntimeException ignored) {
            // A diagnostic failure must never interrupt application startup.
            sink.accept("SIMERTPI_STARTUP_DIAGNOSTIC sampleStatus=unavailable");
        }
    }

    private static String summarize(ThreadInfo info) {
        if (info == null || info.getStackTrace().length == 0) {
            return "none";
        }
        List<String> frames = new ArrayList<>();
        for (StackTraceElement frame : info.getStackTrace()) {
            frames.add(frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber());
        }
        return String.join(",", frames);
    }

    private static String valueOrUnavailable(long value) {
        return value < 0 ? "unavailable" : Long.toString(value);
    }

    private static String deltaOrUnavailable(long current, long previous) {
        return current < 0 || previous < 0 ? "unavailable" : Long.toString(Math.max(0, current - previous));
    }

    private static long processCpuTimeNanos() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            return extended.getProcessCpuTime();
        }
        return -1;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(value -> value >= 0).sum();
    }

    private static long gcTimeMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(value -> value >= 0).sum();
    }

    private record CpuThrottle(long throttledPeriods, long throttledUsec) {
        private static CpuThrottle read() {
            for (Path path : List.of(
                    Path.of("/sys/fs/cgroup/cpu.stat"),
                    Path.of("/sys/fs/cgroup/cpu/cpu.stat"),
                    Path.of("/sys/fs/cgroup/cpu,cpuacct/cpu.stat"))) {
                try {
                    Map<String, Long> values = Files.readAllLines(path).stream()
                            .map(line -> line.trim().split("\\s+"))
                            .filter(parts -> parts.length == 2)
                            .filter(parts -> parts[0].equals("nr_throttled") || parts[0].equals("throttled_usec"))
                            .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parseLong(parts[1]),
                                    (left, right) -> left));
                    if (!values.isEmpty()) {
                        return new CpuThrottle(values.getOrDefault("nr_throttled", -1L),
                                values.getOrDefault("throttled_usec", -1L));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // cgroup layout is platform-specific; unsupported metrics are reported as unavailable.
                }
            }
            return new CpuThrottle(-1, -1);
        }

        private static long parseLong(String raw) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
    }
}
