package gag.nails.booking.internal.booking;

import gag.nails.catalog.model.ServiceKind;
import gag.nails.catalog.model.ServiceView;
import gag.nails.common.error.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Состав записи: ровно одна основная услуга и дополнения без повторов. */
public final class ServiceSelection {

    private ServiceSelection() {}

    /** Идентификаторы в порядке состава: основная услуга первой, затем дополнения. */
    public static List<UUID> ids(UUID mainServiceId, List<UUID> addonServiceIds) {
        if (Set.copyOf(addonServiceIds).size() != addonServiceIds.size()) {
            throw new ValidationException("addonServiceIds", "must not contain duplicates");
        }
        var ids = new ArrayList<UUID>();
        ids.add(mainServiceId);
        ids.addAll(addonServiceIds);
        return List.copyOf(ids);
    }

    /** {@code services} — в порядке {@link #ids}: первая должна быть основной, остальные — дополнениями. */
    public static void requireKinds(List<ServiceView> services) {
        if (services.getFirst().kind() != ServiceKind.MAIN) {
            throw new ValidationException("mainServiceId", "must be a main service");
        }
        if (services.stream().skip(1).anyMatch(service -> service.kind() != ServiceKind.ADDON)) {
            throw new ValidationException("addonServiceIds", "must contain only add-ons");
        }
    }
}
