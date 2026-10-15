package ec.gob.simertpi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SimertpiBackendApplication {

    public static void main(String[] args) {
        StartupDiagnosticsMonitor monitor = StartupDiagnosticsMonitor.startIfEnabled();
        SpringApplication application = new SpringApplication(SimertpiBackendApplication.class);
        application.addListeners((ApplicationReadyEvent event) -> monitor.stop("application_ready"));
        application.run(args);
    }
}
