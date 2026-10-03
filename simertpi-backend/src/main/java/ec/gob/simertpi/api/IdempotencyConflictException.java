package ec.gob.simertpi.api;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("La clave de idempotencia está en uso con otra solicitud o aún está en proceso.");
    }
}
