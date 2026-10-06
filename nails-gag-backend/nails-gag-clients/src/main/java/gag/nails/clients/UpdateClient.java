package gag.nails.clients;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.MonthDay;
import org.jspecify.annotations.Nullable;

/**
 * Правка данных клиента им самим или сотрудником с изменением раздела «Клиенты». Телефон меняет
 * только такой сотрудник ({@link ClientService#changePhone}).
 */
public record UpdateClient(
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) @Nullable String lastName,
        @Size(max = 100) @Nullable String patronymic,
        @Email @Size(max = 254) @Nullable String email,
        @Nullable MonthDay birthday) {}
