package gag.nails.api.internal.clients;

import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code PUT /api/clients/{id}/contact-phone}: номер для связи целиком.
 *
 * @param phone номер в любом привычном написании; {@code null} или пустая строка стирают его
 */
record ChangeContactPhone(@Size(max = 32) @Nullable String phone) {}
