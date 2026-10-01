package gag.nails.clients;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Регистрация через вход (Яндекс ID, VK ID): вызывает модуль {@code identity}, данные — от провайдера.
 *
 * @param phone только подтверждённый провайдером входа (Яндекс ID / VK ID); телефон, введённый
 *     пользователем, сюда не передавать ({@code null})
 */
public record RegisterClient(
        @NotNull UUID accountId,
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 32) @Nullable String phone,
        @Email @Size(max = 254) @Nullable String email) {}
