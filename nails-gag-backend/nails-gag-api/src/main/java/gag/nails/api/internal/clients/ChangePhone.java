package gag.nails.api.internal.clients;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Тело {@code POST /api/clients/{id}/phone}: новый доверенный номер. Написание любое привычное, модуль приводит его к
 * E.164; длина — как у телефона в {@code CreateClient}.
 */
record ChangePhone(@NotBlank @Size(max = 32) String phone) {}
