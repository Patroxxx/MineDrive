package ca.justpatrox.minedrive.data;

import ca.justpatrox.minedrive.MineDRIVE;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class GitManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SYNC_STATE_FILE = ".minedrive-sync.json";

    public static boolean syncEnabled(Minecraft minecraft, String worldId) {
        return syncEnabled(getPath(minecraft, worldId));
    }

    public static boolean syncEnabled(Path worldFolder) {
        return Files.exists(worldFolder.resolve(SYNC_STATE_FILE));
    }

    public static SyncResult pull(Path worldFolder, SyncProgressMonitor progressMonitor) {
        Config config = ConfigManager.getCurrentConfig();
        String accessToken = OAuthManager.getValidAccessToken(config);
        if (accessToken.isBlank()) return SyncResult.FAIL_GENERIC;

        DriveSyncState state = readSyncState(worldFolder);
        if (state == null || state.snapshotFileId == null || state.snapshotFileId.isBlank()) {
            return SyncResult.FAIL_GENERIC;
        }

        Long remoteModified = NetworkManager.getFileModifiedEpochMs(accessToken, state.snapshotFileId);
        if (remoteModified == null) return resolveFailureKind(config);

        if (hasConflict(worldFolder, state, remoteModified)) {
            return SyncResult.FAIL_GENERIC;
        }

        return downloadAndRestore(worldFolder, config, state, progressMonitor);
    }

    public static SyncResult forcePull(Path worldFolder, SyncProgressMonitor progressMonitor) {
        DriveSyncState state = readSyncState(worldFolder);
        if (state == null) return SyncResult.FAIL_GENERIC;

        Config config = ConfigManager.getCurrentConfig();
        return downloadAndRestore(worldFolder, config, state, progressMonitor);
    }

    public static SyncResult push(Path worldFolder, SyncProgressMonitor progressMonitor) {
        Config config = ConfigManager.getCurrentConfig();
        String accessToken = OAuthManager.getValidAccessToken(config);
        if (accessToken.isBlank()) return SyncResult.FAIL_GENERIC;

        DriveSyncState state = readSyncState(worldFolder);
        if (state == null) return SyncResult.FAIL_GENERIC;

        Long remoteModified = NetworkManager.getFileModifiedEpochMs(accessToken, state.snapshotFileId);
        if (remoteModified == null) return resolveFailureKind(config);

        if (hasConflict(worldFolder, state, remoteModified)) {
            return SyncResult.FAIL_GENERIC;
        }

        return doUpload(worldFolder, config, state, progressMonitor);
    }

    public static SyncResult forcePush(Path worldFolder, SyncProgressMonitor progressMonitor) {
        Config config = ConfigManager.getCurrentConfig();
        DriveSyncState state = readSyncState(worldFolder);
        if (state == null) return SyncResult.FAIL_GENERIC;

        return doUpload(worldFolder, config, state, progressMonitor);
    }

    public static boolean init(Minecraft minecraft, String worldId, String folderId, SyncProgressMonitor progressMonitor) {
        Path worldFolder = getPath(minecraft, worldId);
        Config config = ConfigManager.getCurrentConfig();
        String accessToken = OAuthManager.getValidAccessToken(config);
        if (accessToken.isBlank()) return false;

        progressMonitor.beginTask("Preparing sync metadata", 0);
        DriveSyncState state = new DriveSyncState();
        state.worldId = worldId;
        state.folderId = folderId;
        state.snapshotFileId = "";
        state.lastSyncEpochMs = 0L;
        writeSyncState(worldFolder, state);

        SyncResult uploadResult = doUpload(worldFolder, config, state, progressMonitor);
        return uploadResult == SyncResult.SUCCESS;
    }

    public static int cloneRepo(Minecraft minecraft, String folderInput, SyncProgressMonitor progressMonitor) {
        try {
            Config config = ConfigManager.getCurrentConfig();
            String accessToken = OAuthManager.getValidAccessToken(config);
            if (accessToken.isBlank()) return 2;

            progressMonitor.beginTask("Finding Google Drive folder", 0);
            String folderId = NetworkManager.resolveWorldFolderId(accessToken, folderInput);
            if (folderId == null) return 1;

            String snapshotFileId = NetworkManager.findSnapshotFileId(accessToken, folderId);
            if (snapshotFileId == null) return 1;

            String folderName = NetworkManager.getFolderName(accessToken, folderId);
            if (folderName == null || folderName.isBlank()) return 2;

            String baseWorldId = folderName.startsWith("minedrive_") ? folderName.substring("minedrive_".length()) : folderName;
            Path localWorldFolder = getPath(minecraft, baseWorldId);
            int i = 1;
            while (localWorldFolder.toFile().exists()) {
                localWorldFolder = getPath(minecraft, baseWorldId + "_" + i);
                i++;
            }

            Files.createDirectories(localWorldFolder);

            DriveSyncState state = new DriveSyncState();
            state.worldId = localWorldFolder.getFileName().toString();
            state.folderId = folderId;
            state.snapshotFileId = snapshotFileId;
            state.lastSyncEpochMs = 0L;
            writeSyncState(localWorldFolder, state);

            SyncResult result = downloadAndRestore(localWorldFolder, config, state, progressMonitor);
            return result == SyncResult.SUCCESS ? 0 : 2;
        } catch (Exception e) {
            MineDRIVE.LOGGER.error("Failed to clone world from Drive", e);
            return 2;
        }
    }

    public static Path getPath(Minecraft minecraft, String worldId) {
        return minecraft.getLevelSource().getBaseDir().resolve(worldId);
    }

    public static void makeWritable(Minecraft minecraft, String worldId) {
        // No-op with Drive backend. Kept for compatibility with existing mixins.
    }

    public static boolean prune(Minecraft minecraft, String worldId, SyncProgressMonitor progressMonitor) {
        Path worldFolder = getPath(minecraft, worldId);
        return forcePush(worldFolder, progressMonitor) == SyncResult.SUCCESS;
    }

    public static String getLatestLocalCommitDate(Path worldFolder) {
        long epochMs = getLatestLocalChangeEpochMs(worldFolder);
        return formatTimestamp(epochMs);
    }

    public static String getLatestRemoteCommitDate(Path worldFolder) {
        DriveSyncState state = readSyncState(worldFolder);
        if (state == null) return "Remote not found";

        Config config = ConfigManager.getCurrentConfig();
        Long remote = NetworkManager.getFileModifiedEpochMs(OAuthManager.getValidAccessToken(config), state.snapshotFileId);
        if (remote == null) return "Remote not found";
        return formatTimestamp(remote);
    }

    private static SyncResult doUpload(Path worldFolder, Config config, DriveSyncState state, SyncProgressMonitor progressMonitor) {
        if (state.folderId == null || state.folderId.isBlank()) return SyncResult.FAIL_GENERIC;

        Path tempZip = null;
        try {
            progressMonitor.beginTask("Creating world snapshot", 0);
            tempZip = Files.createTempFile("minedrive-upload-", ".zip");
            zipDirectory(worldFolder, tempZip);

            progressMonitor.beginTask("Uploading to Google Drive", 0);
            String snapshotId = NetworkManager.uploadSnapshot(OAuthManager.getValidAccessToken(config), state.folderId, Files.readAllBytes(tempZip));
            if (snapshotId == null) return resolveFailureKind(config);

            Long remoteModified = NetworkManager.getFileModifiedEpochMs(OAuthManager.getValidAccessToken(config), snapshotId);
            if (remoteModified == null) return resolveFailureKind(config);

            state.snapshotFileId = snapshotId;
            state.lastSyncEpochMs = remoteModified;
            writeSyncState(worldFolder, state);
            return SyncResult.SUCCESS;
        } catch (IOException e) {
            MineDRIVE.LOGGER.error("Failed to upload world snapshot", e);
            return SyncResult.FAIL_GENERIC;
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {}
            }
        }
    }

    private static SyncResult downloadAndRestore(Path worldFolder, Config config, DriveSyncState state, SyncProgressMonitor progressMonitor) {
        progressMonitor.beginTask("Downloading cloud snapshot", 0);
        byte[] zipData = NetworkManager.downloadSnapshot(OAuthManager.getValidAccessToken(config), state.snapshotFileId);
        if (zipData == null) return resolveFailureKind(config);

        Path tempZip = null;
        Path tempExtract = null;
        try {
            tempZip = Files.createTempFile("minedrive-download-", ".zip");
            tempExtract = Files.createTempDirectory("minedrive-extract-");
            Files.write(tempZip, zipData);

            progressMonitor.beginTask("Extracting world snapshot", 0);
            unzipToDirectory(tempZip, tempExtract);

            progressMonitor.beginTask("Applying world snapshot", 0);
            clearWorldFolder(worldFolder);
            copyDirectoryContents(tempExtract, worldFolder);

            Long remoteModified = NetworkManager.getFileModifiedEpochMs(OAuthManager.getValidAccessToken(config), state.snapshotFileId);
            if (remoteModified != null) {
                state.lastSyncEpochMs = remoteModified;
                writeSyncState(worldFolder, state);
            }

            return SyncResult.SUCCESS;
        } catch (IOException e) {
            MineDRIVE.LOGGER.error("Failed to restore world snapshot", e);
            return SyncResult.FAIL_GENERIC;
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {}
            }
            if (tempExtract != null) {
                deleteDirectory(tempExtract);
            }
        }
    }

    private static boolean hasConflict(Path worldFolder, DriveSyncState state, long remoteModifiedEpochMs) {
        long lastSync = state.lastSyncEpochMs;
        if (lastSync <= 0) return false;
        boolean localChanged = getLatestLocalChangeEpochMs(worldFolder) > lastSync;
        boolean remoteChanged = remoteModifiedEpochMs > lastSync;
        return localChanged && remoteChanged;
    }

    private static long getLatestLocalChangeEpochMs(Path worldFolder) {
        try (Stream<Path> stream = Files.walk(worldFolder)) {
            return stream
                    .filter(path -> !Files.isDirectory(path))
                    .filter(path -> !path.getFileName().toString().equals(SYNC_STATE_FILE))
                    .mapToLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .max()
                    .orElse(0L);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void zipDirectory(Path sourceDir, Path zipFile) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(path -> !Files.isDirectory(path))
                    .filter(path -> !path.getFileName().toString().equals(SYNC_STATE_FILE))
                    .forEach(files::add);
        }

        try (OutputStream fos = Files.newOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (Path file : files) {
                String relative = sourceDir.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(relative);
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }

    private static void unzipToDirectory(Path zipFile, Path outputDir) throws IOException {
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = outputDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(outputDir)) {
                    throw new IOException("Invalid zip entry path: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    if (resolved.getParent() != null) {
                        Files.createDirectories(resolved.getParent());
                    }
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static void clearWorldFolder(Path worldFolder) throws IOException {
        if (!Files.exists(worldFolder)) return;

        try (Stream<Path> stream = Files.list(worldFolder)) {
            List<Path> children = stream.toList();
            for (Path child : children) {
                if (child.getFileName().toString().equals(SYNC_STATE_FILE)) continue;
                deleteDirectory(child);
            }
        }
    }

    private static void copyDirectoryContents(Path fromDir, Path toDir) throws IOException {
        try (Stream<Path> stream = Files.walk(fromDir)) {
            for (Path source : stream.toList()) {
                Path relative = fromDir.relativize(source);
                Path target = toDir.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteDirectory(Path path) {
        if (!Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private static void writeSyncState(Path worldFolder, DriveSyncState state) {
        try {
            Files.createDirectories(worldFolder);
            Path statePath = worldFolder.resolve(SYNC_STATE_FILE);
            Files.writeString(statePath, GSON.toJson(state));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write sync state", e);
        }
    }

    private static DriveSyncState readSyncState(Path worldFolder) {
        Path statePath = worldFolder.resolve(SYNC_STATE_FILE);
        if (!Files.exists(statePath)) return null;

        try {
            String json = Files.readString(statePath);
            DriveSyncState state = GSON.fromJson(json, DriveSyncState.class);
            return state == null ? null : state;
        } catch (IOException e) {
            return null;
        }
    }

    private static SyncResult resolveFailureKind(Config config) {
        int status = NetworkManager.testCredentials(config.googleAccount, OAuthManager.getValidAccessToken(config));
        if (status == -1) return SyncResult.FAIL_NETWORK;
        return SyncResult.FAIL_GENERIC;
    }

    private static String formatTimestamp(long epochMs) {
        if (epochMs <= 0) return "Unknown";
        Instant instant = Instant.ofEpochMilli(epochMs);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a").withZone(ZoneId.systemDefault());
        return formatter.format(instant);
    }

    private static final class DriveSyncState {
        String worldId;
        String folderId;
        String snapshotFileId;
        long lastSyncEpochMs;
    }
}
