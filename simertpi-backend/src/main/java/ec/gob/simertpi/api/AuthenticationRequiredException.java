package ec.gob.simertpi.api;

public class AuthenticationRequiredException extends RuntimeException {
    public AuthenticationRequiredException() {
        super("Authentication is required");
    }
}
