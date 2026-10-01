package gag.nails.clients;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.MonthDay;
import org.jspecify.annotations.Nullable;

/** Карточка клиента, которую заводит мастер. Телефон принимается в любом привычном написании. */
public record CreateClient(
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) @Nullable String lastName,
        @Size(max = 100) @Nullable String patronymic,
        @Size(max = 32) @Nullable String phone,
        @Email @Size(max = 254) @Nullable String email,
        @Nullable MonthDay birthday) {}
