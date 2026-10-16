package gag.nails.api.internal.auth;

import gag.nails.identity.model.LoginPurpose;
import jakarta.validation.constraints.NotNull;

/** Тело {@code POST /api/auth/{provider}/start}: войти или привязать способ входа к своему аккаунту. */
record StartLogin(@NotNull LoginPurpose purpose) {}
