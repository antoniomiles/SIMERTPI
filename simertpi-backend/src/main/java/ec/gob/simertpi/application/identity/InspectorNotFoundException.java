package ec.gob.simertpi.application.identity;

public class InspectorNotFoundException extends RuntimeException {
    public InspectorNotFoundException() {
        super("No se encontró el inspector.");
    }
}
