package ca.justpatrox.minedrive.data;

public class Config {
    public String googleAccount;
    public String accessTokenEncrypted;
    public String refreshTokenEncrypted;
    public long accessTokenExpiryEpochMs;

    // Legacy fields kept for migration
    public String username;
    public String patEncrypted;

    private transient String accessToken;
    private transient String refreshToken;

    public Config(String googleAccount, String accessTokenEncrypted, String refreshTokenEncrypted, long accessTokenExpiryEpochMs) {
        this.googleAccount = googleAccount;
        this.accessTokenEncrypted = accessTokenEncrypted;
        this.refreshTokenEncrypted = refreshTokenEncrypted;
        this.accessTokenExpiryEpochMs = accessTokenExpiryEpochMs;
    }

    public Config() {
        this("", "", "", 0L);
    }

    public void migrateLegacyFieldsIfNeeded() {
        if ((googleAccount == null || googleAccount.isBlank()) && username != null && !username.isBlank()) {
            googleAccount = username;
        }
        if ((accessTokenEncrypted == null || accessTokenEncrypted.isBlank()) && patEncrypted != null && !patEncrypted.isBlank()) {
            accessTokenEncrypted = patEncrypted;
        }
        if (googleAccount == null) googleAccount = "";
        if (accessTokenEncrypted == null) accessTokenEncrypted = "";
        if (refreshTokenEncrypted == null) refreshTokenEncrypted = "";
    }

    public String getAccessToken() {
        migrateLegacyFieldsIfNeeded();
        if (accessToken == null) {
            accessToken = CryptoManager.decrypt(accessTokenEncrypted);
            if (accessToken == null) accessToken = "";
        }
        return accessToken;
    }

    public String getRefreshToken() {
        migrateLegacyFieldsIfNeeded();
        if (refreshToken == null) {
            refreshToken = CryptoManager.decrypt(refreshTokenEncrypted);
            if (refreshToken == null) refreshToken = "";
        }
        return refreshToken;
    }

    public boolean hasRefreshToken() {
        return !getRefreshToken().isBlank();
    }

    public boolean isAccessTokenMissingOrExpired() {
        if (getAccessToken().isBlank()) return true;
        long now = System.currentTimeMillis();
        return accessTokenExpiryEpochMs <= now + 60_000; // refresh 1 minute before expiry
    }

    public void setGoogleSession(String googleAccount, String accessToken, String refreshToken, long expiresInSeconds) {
        this.googleAccount = googleAccount == null ? "" : googleAccount;
        this.accessTokenEncrypted = CryptoManager.encrypt(accessToken == null ? "" : accessToken);
        this.refreshTokenEncrypted = CryptoManager.encrypt(refreshToken == null ? "" : refreshToken);
        this.accessTokenExpiryEpochMs = System.currentTimeMillis() + Math.max(1L, expiresInSeconds) * 1000L;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public void clearGoogleSession() {
        this.googleAccount = "";
        this.accessTokenEncrypted = "";
        this.refreshTokenEncrypted = "";
        this.accessTokenExpiryEpochMs = 0L;
        this.accessToken = "";
        this.refreshToken = "";
    }

    // Compatibility shim for older callsites during transition
    public String getPat() {
        return getAccessToken();
    }
}
