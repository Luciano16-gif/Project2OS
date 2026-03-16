package ve.edu.unimet.so.project2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Path;
import ve.edu.unimet.so.project2.application.SimulationApplicationState;
import ve.edu.unimet.so.project2.disk.SimulatedDisk;
import ve.edu.unimet.so.project2.journal.JournalManager;
import ve.edu.unimet.so.project2.scheduler.DiskSchedulingPolicy;

public final class SystemPersistenceService {

    private final ObjectMapper objectMapper;
    private final SystemStateMapper systemStateMapper;

    public SystemPersistenceService() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.systemStateMapper = new SystemStateMapper();
    }

    public void save(
            Path path,
            DiskSchedulingPolicy policy,
            SimulatedDisk disk,
            SimulationApplicationState applicationState,
            JournalManager journalManager) {
        requirePath(path);
        PersistedSystemState persistedState =
                systemStateMapper.export(policy, disk, applicationState, journalManager);
        try {
            objectMapper.writeValue(path.toFile(), persistedState);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to save system state: " + path, exception);
        }
    }

    public LoadedSystemState load(Path path) {
        requirePath(path);
        try {
            PersistedSystemState persistedState =
                    objectMapper.readValue(path.toFile(), PersistedSystemState.class);
            return systemStateMapper.importState(persistedState);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load system state: " + path, exception);
        }
    }

    private void requirePath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
    }
}
