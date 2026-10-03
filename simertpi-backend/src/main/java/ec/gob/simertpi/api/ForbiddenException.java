package ec.gob.simertpi.api;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
        super("You are not allowed to access this resource");
    }

    public ForbiddenException(String message) {
        super(message);
    }
}
