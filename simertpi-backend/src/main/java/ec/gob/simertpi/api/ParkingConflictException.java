package ec.gob.simertpi.api;
public class ParkingConflictException extends IllegalArgumentException {
    private final String code;
    private final String userMessage;
    public ParkingConflictException(String code) { this(code, "Parking conditions changed"); }
    public ParkingConflictException(String code, String userMessage) { super(code); this.code=code; this.userMessage=userMessage; }
    public String code() { return code; }
    public String userMessage() { return userMessage; }
}
