package gag.nails.files.internal.storage;

import static gag.nails.files.TestImages.png;
import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.files.FilesTestProperties;
import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.StoredFileView;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.MutableClock;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Уборка «сирот» пачками: размер пачки здесь 2, поэтому три «сироты» уходят за две пачки и пустую третью. */
@ModuleTest
@FilesTestProperties
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrphanBatchesTest {

    private static final Actor UPLOADER =
            Actor.of(UUID.fromString("0198a1f0-0000-7000-8000-000000000003"), null, Map.of());

    @Autowired
    FileStorage storage;

    @Autowired
    StoredFileRepository files;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    StorageLayout layout;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void removeStoredFiles() {
        jdbc.sql("delete from files.stored_file").update();
    }

    @Test
    void orphansAreRemovedInSeveralBatches() {
        var maintenance = new DefaultFileMaintenance(files, transaction, layout, clock, 2);
        var stored = new ArrayList<StoredFileView>();
        for (var i = 0; i < 3; i++) {
            var content = png(64, 48);
            stored.add(
                    storage.store(new ByteArrayInputStream(content), content.length, FilePurpose.REFERENCE, UPLOADER));
        }
        clock.advance(Duration.ofDays(2));

        assertThat(maintenance.removeOrphans()).isEqualTo(3);

        assertThat(files.count()).isZero();
        for (var file : stored) {
            assertThat(layout.directory(file.purpose(), file.id())).doesNotExist();
        }
    }
}
