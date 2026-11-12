package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted, atomic storage for account records. All disk operations run on a worker. */
public final class AccountVault {
    private static final String KEY_ALIAS = "nreader.account.vault.v1";
    private static final String FILE_NAME = "accounts.v1";
    private static final byte[] HEADER = { 'N', 'R', 'A', 1 };
    private static final int IV_LENGTH = 12;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_ACCOUNTS = 64;
    private static final int MAX_FIELD_LENGTH = 8192;
    private static final Object FILE_LOCK = new Object();

    interface KeyProvider {
        SecretKey get() throws GeneralSecurityException, IOException;
    }

    public interface Update {
        @NonNull State apply(@NonNull State current) throws IOException;
    }

    public enum Type { PASSWORD, API_KEY }

    public static final class Account {
        @NonNull public final String id;
        @NonNull public final Type type;
        @NonNull public final String username;
        @Nullable public final String loginName;
        @Nullable public final String avatarUrl;
        @Nullable public final String password;
        @Nullable public final String apiKey;
        @Nullable public final String accessToken;
        @Nullable public final String refreshToken;
        public final long accessExpiry;
        public final long refreshExpiry;
        public final boolean rejected;
        public final long userId;

        public Account(@NonNull String id, @NonNull Type type, @NonNull String username,
                       @Nullable String password, @Nullable String apiKey,
                       @Nullable String accessToken, @Nullable String refreshToken,
                       long accessExpiry, long refreshExpiry, boolean rejected, long userId) {
            this(id, type, username, password, apiKey, accessToken, refreshToken, accessExpiry,
                refreshExpiry, rejected, userId, null, null);
        }

        public Account(@NonNull String id, @NonNull Type type, @NonNull String username,
                       @Nullable String password, @Nullable String apiKey,
                       @Nullable String accessToken, @Nullable String refreshToken,
                       long accessExpiry, long refreshExpiry, boolean rejected, long userId,
                       @Nullable String loginName, @Nullable String avatarUrl) {
            if (id == null || id.isEmpty() || id.length() > 128 || type == null
                || username == null || username.length() > 256
                || (loginName != null && (loginName.isEmpty() || loginName.length() > 256))
                || (avatarUrl != null && (avatarUrl.isEmpty() || avatarUrl.length() > 2048))
                || !validField(password) || !validField(apiKey) || !validField(accessToken)
                || !validField(refreshToken) || accessExpiry < 0 || refreshExpiry < 0 || userId < 0
                || (type == Type.API_KEY && (apiKey == null || apiKey.trim().isEmpty()))
                || (type == Type.API_KEY && (password != null || accessToken != null || refreshToken != null))
                || (type == Type.PASSWORD && apiKey != null)) {
                throw new IllegalArgumentException("Invalid account record");
            }
            this.id = id;
            this.type = type;
            this.username = username;
            this.loginName = loginName;
            this.avatarUrl = avatarUrl;
            this.password = password;
            this.apiKey = apiKey;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.accessExpiry = accessExpiry;
            this.refreshExpiry = refreshExpiry;
            this.rejected = rejected;
            this.userId = userId;
        }

        private static boolean validField(@Nullable String value) {
            return value == null || (!value.isEmpty() && value.length() <= MAX_FIELD_LENGTH);
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("type", type.name());
            object.put("username", username);
            if (loginName != null) object.put("loginName", loginName);
            if (avatarUrl != null) object.put("avatarUrl", avatarUrl);
            if (password != null) object.put("password", password);
            if (apiKey != null) object.put("apiKey", apiKey);
            if (accessToken != null) object.put("accessToken", accessToken);
            if (refreshToken != null) object.put("refreshToken", refreshToken);
            object.put("accessExpiry", accessExpiry);
            object.put("refreshExpiry", refreshExpiry);
            object.put("rejected", rejected);
            object.put("userId", userId);
            return object;
        }

        static Account fromJson(JSONObject object) throws JSONException {
            return new Account(object.getString("id"), Type.valueOf(object.getString("type")),
                object.getString("username"), optionalString(object, "password"),
                optionalString(object, "apiKey"), optionalString(object, "accessToken"),
                optionalString(object, "refreshToken"), object.getLong("accessExpiry"),
                object.getLong("refreshExpiry"), object.getBoolean("rejected"), object.getLong("userId"),
                optionalString(object, "loginName"), optionalString(object, "avatarUrl"));
        }

        @Nullable
        private static String optionalString(JSONObject object, String name) throws JSONException {
            return object.has(name) ? object.getString(name) : null;
        }
    }

    public static final class State {
        @NonNull public final List<Account> accounts;
        @Nullable public final String activeId;

        public State(@NonNull List<Account> accounts, @Nullable String activeId) {
            if (accounts == null || accounts.size() > MAX_ACCOUNTS) throw new IllegalArgumentException("Invalid account list");
            Set<String> ids = new HashSet<>();
            for (Account account : accounts) {
                if (account == null || !ids.add(account.id)) throw new IllegalArgumentException("Duplicate account ID");
            }
            if (activeId != null && !ids.contains(activeId)) throw new IllegalArgumentException("Unknown active account");
            this.accounts = Collections.unmodifiableList(new ArrayList<>(accounts));
            this.activeId = activeId;
        }

        @Nullable public Account activeAccount() {
            if (activeId == null) return null;
            for (Account account : accounts) if (account.id.equals(activeId)) return account;
            return null;
        }
    }

    private final AtomicFile file;
    private final KeyProvider keys;

    public AccountVault(@NonNull Context context) {
        this(new File(context.getNoBackupFilesDir(), FILE_NAME), AccountVault::androidKey);
    }

    AccountVault(File path, KeyProvider keys) {
        this.file = new AtomicFile(path);
        this.keys = keys;
    }

    @WorkerThread
    @NonNull public State load() throws IOException {
        synchronized (FILE_LOCK) {
            if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists())
                return new State(Collections.emptyList(), null);
            try (FileInputStream input = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() + read > MAX_FILE_BYTES) throw new IOException("Account vault is too large");
                    bytes.write(buffer, 0, read);
                }
                byte[] payload = bytes.toByteArray();
                if (payload.length < HEADER.length + IV_LENGTH + 16) throw new IOException("Invalid account vault");
                for (int i = 0; i < HEADER.length; i++) {
                    if (payload[i] != HEADER[i]) throw new IOException("Unsupported account vault");
                }
                byte[] iv = java.util.Arrays.copyOfRange(payload, HEADER.length, HEADER.length + IV_LENGTH);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, keys.get(), new GCMParameterSpec(128, iv));
                cipher.updateAAD(HEADER);
                byte[] plain = cipher.doFinal(payload, HEADER.length + IV_LENGTH,
                    payload.length - HEADER.length - IV_LENGTH);
                JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                JSONArray array = root.getJSONArray("accounts");
                if (array.length() > MAX_ACCOUNTS) throw new IOException("Too many accounts");
                List<Account> accounts = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) accounts.add(Account.fromJson(array.getJSONObject(i)));
                return new State(accounts, root.has("activeId") ? root.getString("activeId") : null);
            } catch (GeneralSecurityException | JSONException | IllegalArgumentException exception) {
                throw new IOException("Unable to read account vault", exception);
            }
        }
    }

    private void save(@NonNull State state) throws IOException {
        synchronized (FILE_LOCK) {
            try {
                JSONObject root = new JSONObject();
                JSONArray array = new JSONArray();
                for (Account account : state.accounts) array.put(account.toJson());
                root.put("accounts", array);
                if (state.activeId != null) root.put("activeId", state.activeId);
                byte[] plain = root.toString().getBytes(StandardCharsets.UTF_8);
                if (plain.length > MAX_FILE_BYTES - HEADER.length - IV_LENGTH - 16)
                    throw new IOException("Account vault is too large");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, keys.get());
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length != IV_LENGTH) throw new GeneralSecurityException("Invalid encryption nonce");
                cipher.updateAAD(HEADER);
                byte[] encrypted = cipher.doFinal(plain);
                FileOutputStream output = null;
                try {
                    output = file.startWrite();
                    output.write(HEADER);
                    output.write(iv);
                    output.write(encrypted);
                    file.finishWrite(output);
                } catch (IOException exception) {
                    if (output != null) file.failWrite(output);
                    throw exception;
                }
            } catch (GeneralSecurityException | JSONException exception) {
                throw new IOException("Unable to save account vault", exception);
            }
        }
    }

    @WorkerThread
    @NonNull public State update(@NonNull Update update) throws IOException {
        synchronized (FILE_LOCK) {
            State next = update.apply(load());
            if (next == null) throw new IOException("Invalid account update");
            save(next);
            return next;
        }
    }

    private static SecretKey androidKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key key = store.getKey(KEY_ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        if (key != null || store.containsAlias(KEY_ALIAS)) throw new GeneralSecurityException("Invalid vault key");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }
}
