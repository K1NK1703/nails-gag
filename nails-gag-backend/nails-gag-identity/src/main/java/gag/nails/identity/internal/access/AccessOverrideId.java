package gag.nails.identity.internal.access;

import gag.nails.common.actor.Section;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.UUID;

/** Составной ключ {@code access_override}. */
@Embeddable
record AccessOverrideId(
        UUID accountId, @Enumerated(EnumType.STRING) Section section) {}
