package ec.gob.simertpi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SimertpiBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimertpiBackendApplication.class, args);
    }
}
