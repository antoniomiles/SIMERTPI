package ec.gob.simertpi.application.identity;

public class InspectorActivationRejectedException extends RuntimeException {
    public InspectorActivationRejectedException() {
        super("El código de activación no es válido, venció o ya se utilizó.");
    }
}
