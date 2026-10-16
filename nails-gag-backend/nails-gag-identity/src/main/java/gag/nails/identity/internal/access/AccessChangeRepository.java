package gag.nails.identity.internal.access;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccessChangeRepository extends JpaRepository<AccessChangeEntity, UUID> {

    /** Новые первыми; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<AccessChangeEntity> findAllByOrderByChangedAtDescIdDesc(Limit limit);

    /** То же постранично; {@code pageable} без сортировки — порядок задан именем метода. */
    Page<AccessChangeEntity> findAllByOrderByChangedAtDescIdDesc(Pageable pageable);
}
