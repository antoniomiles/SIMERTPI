package ec.gob.simertpi.application.notifications;
public record NotificationProviderResult(Status status, String providerCode, String externalMessageId,
 boolean retryable, String errorCode) {
 public enum Status { DELIVERED, TEMPORARY_FAILURE, PERMANENT_FAILURE, INVALID_DESTINATION, UNKNOWN }
 public static NotificationProviderResult failure(Status status, String provider, boolean retryable, String code) {
  return new NotificationProviderResult(status,provider,null,retryable,code);
 }
}
