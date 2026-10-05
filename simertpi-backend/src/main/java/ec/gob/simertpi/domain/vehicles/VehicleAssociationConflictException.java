package ec.gob.simertpi.domain.vehicles;

public class VehicleAssociationConflictException extends RuntimeException {
    public VehicleAssociationConflictException() {
        super("La placa tiene varias asociaciones; consulta por identificador de vehículo.");
    }
}
