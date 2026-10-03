package ec.gob.simertpi.api;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key es obligatorio y debe tener hasta 255 caracteres.");
    }
}
