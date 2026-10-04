package ec.gob.simertpi.testsupport;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/** Preserve scheduler beans for explicit tests without starting background jobs. */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfiguration {

    @Bean
    static BeanFactoryPostProcessor disableAutomaticScheduling() {
        return factory -> {
            if (factory instanceof BeanDefinitionRegistry registry
                    && registry.containsBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
                registry.removeBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
            }
        };
    }
}
