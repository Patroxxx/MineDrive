package ca.justpatrox.minedrive.data;

public interface SyncProgressMonitor {
    void beginTask(String title, int totalWork);

    void update(int completed);

    default void endTask() {}
}
