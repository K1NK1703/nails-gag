package gag.nails.api.internal.clients;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Тело {@code POST /api/clients/{id}/merge}: {@code {id}} в пути — выживающая карточка.
 *
 * @param sourceId вторая карточка того же человека; удаляется, если аккаунт есть не только у неё
 */
record MergeClients(@NotNull UUID sourceId) {}
