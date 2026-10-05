package ec.gob.simertpi.application.dev;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevParkingSeedTest {
    @Test void disabledByDefault() {
        new ApplicationContextRunner().withUserConfiguration(DevParkingSeed.class)
                .run(context -> assertThat(context).doesNotHaveBean(DevParkingSeed.class));
    }
    @Test void forbiddenProfilesCannotTouchDatabaseEvenWhenExplicitlyEnabled() {
        for (String[] profiles : new String[][]{{}, {"prod"}, {"dev", "prod"}, {"dev", "qa"}, {"dev", "uat"}, {"dev", "production"}}) {
            var environment = new MockEnvironment(); environment.setActiveProfiles(profiles);
            var jdbc = mock(JdbcTemplate.class); var transactions = mock(TransactionTemplate.class);
            assertThatThrownBy(() -> new DevParkingSeed(environment, jdbc, transactions).run(null))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(jdbc, transactions);
        }
    }
    @Test void isolatedDevAndDevTestArePermitted() {
        for (String[] profiles : new String[][]{{"dev"}, {"dev", "test"}}) {
            var environment = new MockEnvironment(); environment.setActiveProfiles(profiles);
            assertThatCode(() -> DevParkingSeed.requireDev(environment)).doesNotThrowAnyException();
        }
    }
}
