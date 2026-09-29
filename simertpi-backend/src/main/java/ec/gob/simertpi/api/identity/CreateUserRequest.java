package ec.gob.simertpi.api.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(

    @NotBlank
    @Size(max = 100)
    String username,

    @NotBlank
    @Email
    @Size(max = 255)
    String email,

    @NotBlank
    @Size(max = 100)
    String firstName,

    @NotBlank
    @Size(max = 100)
    String lastName,

    @Size(max = 30)
    String phone
) {
}