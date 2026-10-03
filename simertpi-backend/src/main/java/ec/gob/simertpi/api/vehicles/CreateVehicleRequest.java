package ec.gob.simertpi.api.vehicles;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateVehicleRequest(

    @NotNull
    UUID userId,

    @NotBlank
    @Size(max = 10)
    String plate,

    @Size(max = 100)
    String brand,

    @Size(max = 100)
    String model,

    @Size(max = 50)
    String color
) {
}
