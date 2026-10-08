package ec.gob.simertpi.infrastructure.notifications;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
@ConditionalOnProperty(name = "simertpi.notifications.fcm.enabled", havingValue = "true")
public class FirebasePushConfiguration {
    @Bean(destroyMethod = "delete")
    FirebaseApp simertpiFirebaseApp(@Value("${simertpi.notifications.fcm.project-id:}") String projectId)
            throws IOException {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("FCM project id is required when FCM is enabled");
        }
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId(projectId)
                .build();
        return FirebaseApp.initializeApp(options, "simertpi-fcm");
    }

    @Bean
    FirebaseCloudMessagingClient firebaseCloudMessagingClient(FirebaseApp simertpiFirebaseApp) {
        return new FirebaseAdminMessagingClient(FirebaseMessaging.getInstance(simertpiFirebaseApp));
    }
}
