package ec.gob.simertpi.infrastructure.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EvidenceStorageProperties.class)
public class EvidenceStorageConfiguration { }
