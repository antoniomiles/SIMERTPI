package ec.gob.simertpi.application.identity;

public class InspectorActivationStateException extends RuntimeException {
    public InspectorActivationStateException() {
        super("La cuenta ya no requiere activación.");
    }
}
