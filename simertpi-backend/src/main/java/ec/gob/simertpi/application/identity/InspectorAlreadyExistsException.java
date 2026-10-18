package ec.gob.simertpi.application.identity;

public class InspectorAlreadyExistsException extends RuntimeException {
    public InspectorAlreadyExistsException() {
        super("Ya existe un usuario o correo con esos datos.");
    }
}
