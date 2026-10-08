package ec.gob.simertpi.application.notifications;
import ec.gob.simertpi.infrastructure.notifications.SandboxNotificationProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class NotificationProviderContractTest {
 private NotificationProviderRequest request(String channel){return new NotificationProviderRequest(UUID.randomUUID(),UUID.randomUUID(),channel,"fixture-sensitive-destination","Subject","Body");}
 @Test void unconfiguredFailsClosed(){
  var registry=new NotificationProviderRegistry(new MockEnvironment(),List.of());
  var result=registry.send(registry.configuredCode("EMAIL"),request("EMAIL"));
  assertThat(result.status()).isEqualTo(NotificationProviderResult.Status.TEMPORARY_FAILURE);assertThat(result.externalMessageId()).isNull();assertThat(result.errorCode()).isEqualTo("PROVIDER_NOT_CONFIGURED");
 }
 @Test void unknownProviderFailsClosed(){var result=new NotificationProviderRegistry(new MockEnvironment(),List.of()).send("MISSING",request("PUSH"));assertThat(result.status()).isEqualTo(NotificationProviderResult.Status.PERMANENT_FAILURE);assertThat(result.externalMessageId()).isNull();}
 @Test void selectingFcmWithoutExplicitlyEnabledProviderFailsStartup(){
  var registry=new NotificationProviderRegistry(new MockEnvironment().withProperty("simertpi.notifications.providers.push","FCM"),List.of());
  assertThatThrownBy(registry::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("FCM");
 }
 @ParameterizedTest @EnumSource(value=NotificationProviderResult.Status.class)
 void sandboxOutcomes(NotificationProviderResult.Status outcome){
  var env=new MockEnvironment().withProperty("simertpi.notifications.sandbox.enabled","true").withProperty("simertpi.notifications.sandbox.outcome",outcome.name());env.setActiveProfiles("test");
  var provider=new SandboxNotificationProvider(env);provider.validate();
  for(String channel:List.of("PUSH","WHATSAPP","EMAIL")){
   var result=provider.send(request(channel));assertThat(result.status()).isEqualTo(outcome);assertThat(result.retryable()).isEqualTo(outcome==NotificationProviderResult.Status.TEMPORARY_FAILURE);
   if(outcome!=NotificationProviderResult.Status.DELIVERED)assertThat(result.externalMessageId()).isNull();
  }
 }
 @ParameterizedTest @ValueSource(strings={"prod","production","test,prod",""})
 void sandboxRefusesUnsafeProfiles(String profiles){
  var env=new MockEnvironment().withProperty("simertpi.notifications.sandbox.enabled","true");env.setActiveProfiles(profiles.isEmpty()?new String[0]:profiles.split(","));
  var provider=new SandboxNotificationProvider(env);assertThatThrownBy(provider::validate).isInstanceOf(IllegalStateException.class);
  assertThat(provider.send(request("EMAIL")).status()).isEqualTo(NotificationProviderResult.Status.PERMANENT_FAILURE);
 }
 @Test void sandboxRequiresExplicitOptIn(){var env=new MockEnvironment();env.setActiveProfiles("test");assertThat(new SandboxNotificationProvider(env).send(request("PUSH")).status()).isEqualTo(NotificationProviderResult.Status.PERMANENT_FAILURE);}
 @Test void providerCannotSendWrongChannel(){
  NotificationProvider push=new NotificationProvider(){public String code(){return "PUSH_ONLY";}public Set<String> channels(){return Set.of("PUSH");}public NotificationProviderResult send(NotificationProviderRequest request){throw new AssertionError("wrong channel called provider");}};
  assertThat(new NotificationProviderRegistry(new MockEnvironment(),List.of(push)).send("PUSH_ONLY",request("EMAIL")).errorCode()).isEqualTo("PROVIDER_UNKNOWN");
 }
 @Test void requestToStringRedactsDestinationAndBody(){assertThat(request("PUSH").toString()).doesNotContain("fixture-sensitive-destination","Subject","Body");}

 @Test void templateRequiresVariables(){assertThatThrownBy(()->NotificationTemplateRenderer.render("Hello {name}",Map.of(),200)).isInstanceOf(IllegalStateException.class);}
 @Test void templateDoesNotInterpretHtmlOrTechnicalObjects(){assertThat(NotificationTemplateRenderer.render("Hello {name}",Map.of("name","<script>bad</script>"),200)).isEqualTo("Hello &lt;script&gt;bad&lt;/script&gt;");assertThatThrownBy(()->NotificationTemplateRenderer.render("{name}",Map.of("name",new Object()),200)).isInstanceOf(IllegalStateException.class);}
 @Test void templateValuesAreNotRecursivelyInterpreted(){assertThat(NotificationTemplateRenderer.render("{value}",Map.of("value","{another}"),200)).isEqualTo("{another}");}
}
