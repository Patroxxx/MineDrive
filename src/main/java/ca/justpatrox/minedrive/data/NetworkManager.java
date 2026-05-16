package ca.justpatrox.minedrive.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NetworkManager {
    public static boolean hasValidCredentials = false;

    private static final HttpClient CLIENT = HttpClient.newBuilder().build();
    private static final String DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files";
    private static final String DRIVE_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String APP_FOLDER_NAME = "MineDrive Worlds";
    private static final String SNAPSHOT_FILE_NAME = "world-snapshot.zip";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern DRIVE_FOLDER_URL = Pattern.compile(".*/folders/([a-zA-Z0-9_-]+).*");

    public static int testCredentials(String googleAccount, String accessToken) {
        String email = fetchAuthorizedGoogleEmail(accessToken);
        if (email == null) {
            hasValidCredentials = false;
            return -1;
        }
        hasValidCredentials = true;
        if (googleAccount != null && !googleAccount.isBlank() && !email.equalsIgnoreCase(googleAccount.trim())) {
            return 404;
        }
        return 200;
    }

    public static String fetchAuthorizedGoogleEmail(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)"))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject user = root.getAsJsonObject("user");
            if (user == null || !user.has("emailAddress")) return null;
            return user.get("emailAddress").getAsString();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    public static String createWorldFolder(String accessToken, String worldId, String worldName) {
        String appFolderId = ensureAppFolder(accessToken);
        if (appFolderId == null) return null;

        String existing = findWorldFolderId(accessToken, worldId);
        if (existing != null) return existing;

        JsonObject body = new JsonObject();
        body.addProperty("name", "minedrive_" + worldId);
        body.addProperty("mimeType", "application/vnd.google-apps.folder");
        JsonArray parents = new JsonArray();
        parents.add(appFolderId);
        body.add("parents", parents);
        JsonObject appProperties = new JsonObject();
        appProperties.addProperty("worldName", worldName);
        appProperties.addProperty("worldId", worldId);
        body.add("appProperties", appProperties);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_ENDPOINT))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return root.get("id").getAsString();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    public static String resolveWorldFolderId(String accessToken, String folderInput) {
        if (folderInput == null || folderInput.isBlank()) return null;
        String normalizedInput = normalizeFolderInput(folderInput);

        String byIdName = getFolderName(accessToken, normalizedInput);
        if (byIdName != null) return normalizedInput;

        String escaped = normalizedInput.replace("'", "\\'");
        String query = "mimeType='application/vnd.google-apps.folder' and trashed=false and name='" + escaped + "'";
        JsonArray files = searchFiles(accessToken, query, "files(id,name)");
        if (files == null || files.isEmpty()) return null;
        return files.get(0).getAsJsonObject().get("id").getAsString();
    }

    public static String findSnapshotFileId(String accessToken, String folderId) {
        String query = "'" + folderId + "' in parents and trashed=false and name='" + SNAPSHOT_FILE_NAME + "'";
        JsonArray files = searchFiles(accessToken, query, "files(id,modifiedTime)");
        if (files == null || files.isEmpty()) return null;
        return files.get(0).getAsJsonObject().get("id").getAsString();
    }

    public static String uploadSnapshot(String accessToken, String folderId, byte[] zipBytes) {
        String existingSnapshotId = findSnapshotFileId(accessToken, folderId);
        if (existingSnapshotId != null) {
            if (updateSnapshot(accessToken, existingSnapshotId, zipBytes)) {
                return existingSnapshotId;
            }
            return null;
        }

        String boundary = "MineDriveBoundary" + System.nanoTime();
        String metadata = "{\"name\":\"" + SNAPSHOT_FILE_NAME + "\",\"parents\":[\"" + folderId + "\"]}";

        byte[] payload = MultipartBuilder.build(boundary, metadata, zipBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_UPLOAD_ENDPOINT + "?uploadType=multipart"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return root.get("id").getAsString();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    public static boolean updateSnapshot(String accessToken, String fileId, byte[] zipBytes) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_UPLOAD_ENDPOINT + "/" + fileId + "?uploadType=media"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/zip")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(zipBytes))
                .build();

        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public static byte[] downloadSnapshot(String accessToken, String fileId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_ENDPOINT + "/" + fileId + "?alt=media"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;
            return response.body();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    public static Long getFileModifiedEpochMs(String accessToken, String fileId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_ENDPOINT + "/" + fileId + "?fields=modifiedTime"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String modified = root.get("modifiedTime").getAsString();
            return Instant.parse(modified).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean deleteWorldFolder(String accessToken, String folderId) {
        if (folderId == null || folderId.isBlank()) return false;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_ENDPOINT + "/" + folderId))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(REQUEST_TIMEOUT)
                .DELETE()
                .build();

        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 204 || response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public static String getFolderName(String accessToken, String folderId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DRIVE_FILES_ENDPOINT + "/" + folderId + "?fields=id,name,mimeType"))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String mimeType = root.get("mimeType").getAsString();
            if (!"application/vnd.google-apps.folder".equals(mimeType)) return null;
            return root.get("name").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    public static List<WorldFolderInfo> listWorldFolders(String accessToken) {
        List<WorldFolderInfo> worlds = new ArrayList<>();
        String appFolderId = findAppFolderId(accessToken);
        if (appFolderId == null) return worlds;

        String query = "'" + appFolderId + "' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false";
        JsonArray files = searchFiles(accessToken, query, "files(id,name,appProperties)");
        if (files == null) return worlds;

        for (JsonElement element : files) {
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            if (!file.has("id") || !file.has("name")) continue;

            String id = file.get("id").getAsString();
            String folderName = file.get("name").getAsString();
            String displayName = folderName;
            if (folderName.startsWith("minedrive_")) {
                displayName = folderName.substring("minedrive_".length());
            }

            JsonObject appProperties = file.has("appProperties") && file.get("appProperties").isJsonObject()
                    ? file.getAsJsonObject("appProperties")
                    : null;
            if (appProperties != null && appProperties.has("worldName")) {
                String worldName = appProperties.get("worldName").getAsString();
                if (!worldName.isBlank()) displayName = worldName;
            }

            worlds.add(new WorldFolderInfo(id, folderName, displayName));
        }

        worlds.sort(Comparator.comparing(world -> world.displayName.toLowerCase()));
        return worlds;
    }

    private static String findWorldFolderId(String accessToken, String worldId) {
        String query = "mimeType='application/vnd.google-apps.folder' and trashed=false and name='minedrive_" + worldId + "'";
        JsonArray files = searchFiles(accessToken, query, "files(id,name)");
        if (files == null || files.isEmpty()) return null;
        return files.get(0).getAsJsonObject().get("id").getAsString();
    }

    private static String ensureAppFolder(String accessToken) {
        String query = "mimeType='application/vnd.google-apps.folder' and trashed=false and name='" + APP_FOLDER_NAME + "'";
        JsonArray files = searchFiles(accessToken, query, "files(id,name)");
        if (files != null && !files.isEmpty()) {
            return files.get(0).getAsJsonObject().get("id").getAsString();
        }

        JsonObject body = new JsonObject();
        body.addProperty("name", APP_FOLDER_NAME);
        body.addProperty("mimeType", "application/vnd.google-apps.folder");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_ENDPOINT))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return root.get("id").getAsString();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String findAppFolderId(String accessToken) {
        String query = "mimeType='application/vnd.google-apps.folder' and trashed=false and name='" + APP_FOLDER_NAME + "'";
        JsonArray files = searchFiles(accessToken, query, "files(id,name)");
        if (files == null || files.isEmpty()) return null;
        return files.get(0).getAsJsonObject().get("id").getAsString();
    }

    private static JsonArray searchFiles(String accessToken, String q, String fields) {
        String encodedQuery = URLEncoder.encode(q, StandardCharsets.UTF_8);
        String encodedFields = URLEncoder.encode(fields, StandardCharsets.UTF_8);
        String uri = DRIVE_FILES_ENDPOINT + "?q=" + encodedQuery + "&fields=" + encodedFields + "&pageSize=25";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonElement element = root.get("files");
            if (element == null || !element.isJsonArray()) return new JsonArray();
            return element.getAsJsonArray();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String normalizeFolderInput(String folderInput) {
        String trimmed = folderInput.trim();
        Matcher matcher = DRIVE_FOLDER_URL.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return trimmed;
    }

    private static final class MultipartBuilder {
        private MultipartBuilder() {}

        private static byte[] build(String boundary, String metadataJson, byte[] fileContent) {
            String part1 = "--" + boundary + "\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    + metadataJson + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Type: application/zip\r\n\r\n";
            String part3 = "\r\n--" + boundary + "--\r\n";

            byte[] p1 = part1.getBytes(StandardCharsets.UTF_8);
            byte[] p3 = part3.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[p1.length + fileContent.length + p3.length];
            System.arraycopy(p1, 0, out, 0, p1.length);
            System.arraycopy(fileContent, 0, out, p1.length, fileContent.length);
            System.arraycopy(p3, 0, out, p1.length + fileContent.length, p3.length);
            return out;
        }
    }

    public static final class WorldFolderInfo {
        public final String id;
        public final String folderName;
        public final String displayName;

        private WorldFolderInfo(String id, String folderName, String displayName) {
            this.id = id;
            this.folderName = folderName;
            this.displayName = displayName;
        }
    }
}
