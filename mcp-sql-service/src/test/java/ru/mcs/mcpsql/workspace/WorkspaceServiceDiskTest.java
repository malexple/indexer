package ru.mcs.mcpsql.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceServiceDiskTest {

    @TempDir
    Path tmp;

    @Test
    void uploadsAndPatchesAreRefusedWhenTheDiskIsTooFull() throws Exception {
        ServiceProperties properties = new ServiceProperties(tmp.resolve("data"), "r", "w", 8000, 3, 200, 50);
        WorkspaceService service = new WorkspaceService(properties,
                MultiUserProperties.of(true, 50, 5).withMinFreeDiskMb(Integer.MAX_VALUE));

        assertThatThrownBy(() -> service.putDatabase("1/a", new ByteArrayInputStream(new byte[10])))
                .isInstanceOf(InsufficientStorageException.class);
        assertThatThrownBy(() -> service.applyPatch("1/a", new ByteArrayInputStream(new byte[10])))
                .isInstanceOf(InsufficientStorageException.class);
    }
}
