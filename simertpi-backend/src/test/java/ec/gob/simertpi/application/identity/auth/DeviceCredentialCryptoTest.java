package ec.gob.simertpi.application.identity.auth;
import org.junit.jupiter.api.Test;
import java.security.*;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
class DeviceCredentialCryptoTest {
 @Test void verifiesRealRsaSignatureAndRejectsAlteration() throws Exception {
  var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
  var signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());signature.update("challenge".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  String proof=Base64.getEncoder().encodeToString(signature.sign()),key=Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
  assertThat(DeviceCredentialService.validSignature(key,"challenge",proof)).isTrue();
  assertThat(DeviceCredentialService.validSignature(key,"changed",proof)).isFalse();
  assertThat(DeviceCredentialService.validSignature(key,"challenge","invalid")).isFalse();
 }
 @Test void rejectsWeakAndNonRsaKeys() throws Exception {
  var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(1024);
  assertThatThrownBy(()->DeviceCredentialService.publicKey(Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()))).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
  assertThat(DeviceCredentialService.validSignature("invalid","challenge","invalid")).isFalse();
 }
}
