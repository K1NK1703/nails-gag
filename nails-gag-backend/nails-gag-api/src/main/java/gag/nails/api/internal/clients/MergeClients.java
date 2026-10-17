package gag.nails.api.internal.clients;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Тело {@code POST /api/clients/{id}/merge}: {@code {id}} в пути — карточка, которая остаётся.
 *
 * @param sourceId вторая карточка того же человека; удаляется, а если аккаунт входа есть только у неё — остаётся
 *     она, и удаляется {@code {id}}
 */
record MergeClients(@NotNull UUID sourceId) {}
