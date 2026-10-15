package ec.gob.simertpi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class StartupDiagnosticsMonitorTest {

    @Test
    void disabledMonitorDoesNotScheduleOrEmitLogs() {
        List<String> records = new CopyOnWriteArrayList<>();

        StartupDiagnosticsMonitor monitor = new StartupDiagnosticsMonitor(
                Thread.currentThread(), Duration.ofMillis(10), Duration.ofMillis(80), records::add, false);
        monitor.stop("application_ready");

        assertTrue(monitor.isStopped());
        assertTrue(monitor.monitorThreadIsDaemon());
        assertTrue(records.isEmpty());
    }

    @Test
    void enabledMonitorSamplesAndStopsAtItsBoundedDuration() throws InterruptedException {
        List<String> records = new CopyOnWriteArrayList<>();
        StartupDiagnosticsMonitor monitor = new StartupDiagnosticsMonitor(
                Thread.currentThread(), Duration.ofMillis(20), Duration.ofMillis(120), records::add, true);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!monitor.isStopped() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertTrue(monitor.isStopped(), "monitor should stop at its configured maximum duration");
        assertTrue(monitor.monitorThreadIsDaemon(), "diagnostics thread must not keep the JVM alive");
        assertTrue(records.stream().anyMatch(record -> record.startsWith("SIMERTPI_STARTUP_DIAGNOSTIC elapsedMs=")));
        assertTrue(records.stream().anyMatch(record -> record.contains("stopped reason=max_duration")));
        assertFalse(records.stream().anyMatch(record -> record.matches("(?i).*\\b(password|token|credential)=.*")));
    }
}
