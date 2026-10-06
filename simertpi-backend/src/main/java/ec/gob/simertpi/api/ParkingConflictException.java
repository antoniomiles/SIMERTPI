package ec.gob.simertpi.api;
public class ParkingConflictException extends IllegalArgumentException {
    private final String code;
    public ParkingConflictException(String code) { super(code); this.code=code; }
    public String code() { return code; }
}
