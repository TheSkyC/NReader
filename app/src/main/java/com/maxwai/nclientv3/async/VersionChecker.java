// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.JsonReader;
import android.util.JsonToken;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.Lifecycle;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.async.update.UpdatePhase;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class VersionChecker {
    private static final int INSTALL_REQUEST_CODE = 1202;
    static final long MAX_UPDATE_SIZE_BYTES = 256L * 1024L * 1024L;
    private static final ExecutorService INSTALL_VALIDATOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean INSTALL_VALIDATION_ACTIVE = new AtomicBoolean();
    public interface InstallLauncher {
        void launch(@NonNull Intent intent);
    }
    public interface Listener {
        void onUpdateState(@NonNull UpdateState state);
        void onReleaseAvailable(@NonNull GitHubRelease release);
    }

    private static final String RELEASE_API_URL = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPOSITORY + "/releases";
    private static final String LATEST_RELEASE_URL = "https://github.com/" + BuildConfig.UPDATE_REPOSITORY + "/releases/latest";
    private static String latest = null;
    private final AppCompatActivity context;
    private final UpdateStateStore stateStore;
    private final UpdateMetadataStore metadataStore;
    private final OkHttpClient updateClient = new OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .writeTimeout(60L, TimeUnit.SECONDS)
        .callTimeout(15L, TimeUnit.MINUTES)
        .build();
    @Nullable private final Listener listener;
    @Nullable private InstallLauncher installLauncher;
    @Nullable private GitHubRelease latestRelease;
    private String downloadUrl;
    @Nullable private Call metadataCall;
    @Nullable private Call manifestCall;
    @Nullable private volatile Call downloadCall;
    @Nullable private volatile String downloadPath;
    private volatile boolean cancelled;
    private volatile long checkRevision = -1L;

    public VersionChecker(AppCompatActivity context, final boolean silent) {
        this(context, silent, null, true);
    }

    public VersionChecker(AppCompatActivity context, final boolean silent, @Nullable Listener listener) {
        this(context, silent, listener, true);
    }

    public VersionChecker(
        AppCompatActivity context,
        final boolean silent,
        @Nullable Listener listener,
        boolean startCheck
    ) {
        boolean withPrerelease = Global.isEnableBeta();
        this.context = context;
        this.stateStore = new UpdateStateStore(context);
        this.metadataStore = new UpdateMetadataStore(context);
        this.listener = listener;
        if (BuildConfig.UPDATE_REPOSITORY.isEmpty() && BuildConfig.UPDATE_ENDPOINT.isEmpty()) return;
        restoreSelectedRelease();
        if (!startCheck) {
            return;
        }
        if (latest != null && Global.hasStoragePermission(context)) {
            downloadVersion(latest);
            latest = null;
            return;
        }
        long revision = stateStore.revision();
        boolean[] started = {false};
        stateStore.runIfUnchanged(revision, () -> {
            UpdatePhase phase = stateStore.load().getPhase();
            if (phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.VERIFYING
                || phase == UpdatePhase.READY_TO_INSTALL || phase == UpdatePhase.INSTALLING) return;
            metadataStore.markAttempt(System.currentTimeMillis());
            saveState(UpdateState.checking(System.currentTimeMillis()));
            checkRevision = stateStore.revision();
            started[0] = true;
        });
        if (!started[0]) return;
        LogUtility.d("ACTUAL VERSION: " + BuildConfig.VERSION_NAME);
        requestReleases(withPrerelease, silent, true);
    }

    private void requestReleases(boolean withPrerelease, boolean silent, boolean useConditionalRequest) {
        Request.Builder request = new Request.Builder()
            .url(releaseApiUrl())
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME);
        if (useConditionalRequest && metadataStore.getEtag() != null) {
            request.header("If-None-Match", metadataStore.getEtag());
        }
        metadataCall = updateClient.newCall(request.build());
        metadataCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (call.isCanceled() || call != metadataCall) return;
                notifyRetrievalFailure(e, silent);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (call != metadataCall) {
                    response.close();
                    return;
                }
                try {
                    String json;
                    boolean retryWithoutCache = false;
                    try (Response ignored = response) {
                        if (response.code() == 304) {
                            json = metadataStore.getBody();
                            retryWithoutCache = json == null;
                        } else if (response.isSuccessful() && response.body() != null) {
                            json = UpdateResponseReader.read(response.body(), UpdateResponseReader.RELEASE_LIMIT);
                            metadataStore.save(json, response.header("ETag"));
                        } else {
                            throw new IOException("Unable to retrieve release metadata: HTTP " + response.code());
                        }
                    }
                    if (retryWithoutCache) {
                        requestReleases(withPrerelease, silent, false);
                        return;
                    }
                    GitHubRelease release;
                    try (JsonReader jr = new JsonReader(new StringReader(json))) {
                        release = parseVersionJson(jr, withPrerelease, BuildConfig.FLAVOR);
                    }
                    if (release == null) {
                        release = new GitHubRelease();
                        release.versionCode = BuildConfig.VERSION_NAME;
                    }
                    if (release.manifestUrl != null) {
                        requestManifest(release, withPrerelease, silent);
                    } else {
                        publishRelease(release, silent);
                    }
                } catch (IOException | RuntimeException e) {
                    notifyRetrievalFailure(e, silent);
                }
            }
        });
    }

    private void requestManifest(
        @NonNull GitHubRelease apiRelease,
        boolean withPrerelease,
        boolean silent
    ) {
        if (apiRelease.manifestUrl == null || !isAllowedUpdateUrl(apiRelease.manifestUrl)) {
            publishRelease(apiRelease, silent);
            return;
        }
        if (manifestCall != null) manifestCall.cancel();
        manifestCall = updateClient.newCall(new Request.Builder()
            .url(apiRelease.manifestUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME)
            .build());
        manifestCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (call.isCanceled() || call != manifestCall) return;
                publishRelease(apiRelease, silent);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (call != manifestCall) {
                    response.close();
                    return;
                }
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        publishRelease(apiRelease, silent);
                        return;
                    }
                    GitHubRelease manifestRelease;
                    String manifest = UpdateResponseReader.read(response.body(), UpdateResponseReader.MANIFEST_LIMIT);
                    try (JsonReader reader = new JsonReader(new StringReader(manifest))) {
                        manifestRelease = parseManifestJson(reader, withPrerelease, BuildConfig.FLAVOR, context.getPackageName());
                    }
                    if (call.isCanceled() || call != manifestCall) return;
                    publishRelease(manifestRelease == null ? apiRelease : manifestRelease, silent);
                } catch (IOException | RuntimeException ignored) {
                    if (call.isCanceled() || call != manifestCall) return;
                    publishRelease(apiRelease, silent);
                }
            }
        });
    }

    private void publishRelease(@NonNull GitHubRelease release, boolean silent) {
        context.runOnUiThread(() -> runIfCurrentCheck(() -> {
            downloadUrl = release.downloadUrl;
            metadataStore.markSuccessfulCheck(System.currentTimeMillis());
            boolean newer;
            try {
                newer = release.isNewerThanInstalled(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
            } catch (IllegalArgumentException ignored) {
                newer = false;
            }
            if (downloadUrl == null || !isAllowedUpdateUrl(downloadUrl) || !isValidSha256(release.sha256) || !newer) {
                metadataStore.clearSelectedRelease();
                saveState(UpdateState.idle(System.currentTimeMillis()));
                if (!silent) Toast.makeText(context, R.string.no_updates_found, Toast.LENGTH_SHORT).show();
            } else {
                latestRelease = release;
                metadataStore.saveSelectedRelease(
                    release.versionCode,
                    release.releaseVersionCode,
                    release.downloadUrl,
                    release.body,
                    release.beta,
                    release.sha256,
                    release.sizeBytes
                );
                saveState(UpdateState.available(release.versionCode, System.currentTimeMillis()));
                if (listener == null) createDialog(release);
                else listener.onReleaseAvailable(release);
            }
        }));
    }

    private void notifyRetrievalFailure(Exception exception, boolean silent) {
        runIfCurrentCheck(() -> {
            saveState(UpdateState.error(null, null, "metadata", System.currentTimeMillis()));
            context.runOnUiThread(() -> {
                LogUtility.e("Update metadata request failed: " + exception.getClass().getSimpleName());
                if (!silent)
                    Toast.makeText(context, R.string.error_retrieving, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void runIfCurrentCheck(@NonNull Runnable action) {
        long revision = checkRevision;
        if (revision < 0L) return;
        stateStore.runIfUnchanged(revision, () -> {
            if (!cancelled && stateStore.load().getPhase() == UpdatePhase.CHECKING) action.run();
        });
    }

    private void saveState(@NonNull UpdateState state) {
        stateStore.save(state);
        UpdateNotification.publish(context, state);
        if (listener != null) {
            context.runOnUiThread(() -> listener.onUpdateState(stateStore.load()));
        }
    }

    public void startDownload() {
        GitHubRelease release = latestRelease;
        if (release == null || release.versionCode == null) return;
        downloadVersion(release.versionCode);
    }

    public void setInstallLauncher(@Nullable InstallLauncher launcher) {
        installLauncher = launcher;
    }

    private void restoreSelectedRelease() {
        if (stateStore.load().getPhase() != UpdatePhase.AVAILABLE) return;
        String version = metadataStore.getSelectedVersion();
        String url = metadataStore.getSelectedUrl();
        String sha256 = metadataStore.getSelectedSha256();
        if (version == null || url == null || !isAllowedUpdateUrl(url) || !isValidSha256(sha256)) return;
        GitHubRelease restored = new GitHubRelease();
        restored.versionCode = version;
        restored.releaseVersionCode = metadataStore.getSelectedVersionCode();
        restored.downloadUrl = url;
        restored.body = metadataStore.getSelectedBody();
        restored.beta = metadataStore.isSelectedBeta();
        restored.sha256 = sha256;
        restored.sizeBytes = metadataStore.getSelectedSize();
        try {
            if (!restored.isNewerThanInstalled(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) return;
        } catch (IllegalArgumentException ignored) {
            return;
        }
        latestRelease = restored;
        downloadUrl = url;
        if (listener != null) listener.onReleaseAvailable(restored);
    }

    public boolean hasActiveDownload() {
        Call call = downloadCall;
        String path = downloadPath;
        UpdateState state = stateStore.load();
        return call != null && !call.isCanceled() && path != null
            && state.getPhase() == UpdatePhase.DOWNLOADING && path.equals(state.getFilePath());
    }

    public void cancel() {
        cancelled = true;
        if (metadataCall != null) metadataCall.cancel();
        if (manifestCall != null) manifestCall.cancel();
        stopDownload(false);
    }

    public void cancelDownload() {
        stopDownload(true);
    }

    private void stopDownload(boolean requestedByUser) {
        String path = downloadPath;
        Call call = downloadCall;
        if (path != null) {
            stateStore.runIfDownloading(path, () -> {
                UpdateState state = stateStore.load();
                if (requestedByUser && state.getVersion() != null) {
                    saveState(UpdateState.available(state.getVersion(), System.currentTimeMillis()));
                } else {
                    saveState(UpdateState.error(state.getVersion(), path, "interrupted_download", System.currentTimeMillis()));
                }
                UpdateNotification.clear(context);
            });
        }
        if (call != null) call.cancel();
        if (path != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        }
    }

    public boolean installReadyUpdate() {
        UpdateState state = stateStore.load();
        if (!state.isReadyToInstall() || state.getFilePath() == null) return false;
        File file = new File(state.getFilePath());
        if (!file.isFile()) {
            saveState(UpdateState.error(state.getVersion(), state.getFilePath(), "missing_file", System.currentTimeMillis()));
            return false;
        }
        return installApp(file);
    }

    public void onInstallActivityResult(int resultCode) {
        if (resultCode == android.app.Activity.RESULT_CANCELED) {
            UpdateState state = stateStore.load();
            if (state.getFilePath() != null && new File(state.getFilePath()).isFile()) {
                UpdateNotification.resetReady(context);
                saveState(UpdateState.readyToInstall(
                    state.getVersion() == null ? BuildConfig.VERSION_NAME : state.getVersion(),
                    state.getFilePath(),
                    new File(state.getFilePath()).length(),
                    System.currentTimeMillis()
                ));
            }
        }
    }

    static GitHubRelease parseVersionJson(JsonReader jr, boolean withPrerelease, String flavor) throws IOException {
        List<GitHubRelease> candidates = new ArrayList<>();
        try {
            jr.beginArray();
            while (jr.hasNext()) {
                GitHubRelease release = parseVersion(jr, withPrerelease, flavor);
                if (release != null) candidates.add(release);
            }
        } catch (IllegalStateException ignore) {
        }
        return selectNewestCompatible(candidates);
    }

    static GitHubRelease selectNewestCompatible(List<GitHubRelease> candidates) {
        GitHubRelease newest = null;
        for (GitHubRelease candidate : candidates) {
            if (candidate == null || candidate.versionCode == null || candidate.downloadUrl == null) continue;
            try {
                GitHubRelease.ParsedVersion.parse(candidate.versionCode, "Release");
                if (newest == null || candidate.isNewerThenVersion(newest.versionCode)) {
                    newest = candidate;
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed release tags and continue with valid candidates.
            }
        }
        return newest;
    }

    private static GitHubRelease parseVersion(JsonReader jr, boolean withPrerelease, String flavor) throws IOException {
        GitHubRelease release = new GitHubRelease();
        boolean invalid = false;
        jr.beginObject();

        while (jr.peek() != JsonToken.END_OBJECT) {
            switch (jr.nextName()) {
                case "tag_name":
                    release.versionCode = GitHubRelease.normalizeVersion(jr.nextString());
                    break;
                case "body":
                    release.body = jr.nextString();
                    break;
                case "prerelease":
                    release.beta = jr.nextBoolean();
                    if (release.beta && !withPrerelease)
                        invalid = true;
                    break;
                case "assets":
                    jr.beginArray();
                    while (jr.hasNext()) {
                        AssetCandidate candidate = getAsset(jr);
                        if (candidate == null) continue;
                        if ("nreader-update.json".equals(candidate.name)) {
                            release.manifestUrl = candidate.url;
                        } else if (release.downloadUrl == null && matchesFlavorAsset(candidate.name, flavor)) {
                            if (isAllowedUpdateUrl(candidate.url) && isValidSha256(candidate.sha256)) {
                                release.downloadUrl = candidate.url;
                                release.sha256 = candidate.sha256;
                            }
                        }
                    }
                    jr.endArray();
                    break;
                default:
                    jr.skipValue();
                    break;
            }
        }
        jr.endObject();

        return invalid ? null : release;
    }

    private static boolean isValidSha256(@Nullable String value) {
        if (value == null) return false;
        String normalized = value.startsWith("sha256:") ? value.substring(7) : value;
        return normalized.matches("(?i)^[0-9a-f]{64}$");
    }

    static GitHubRelease parseManifestJson(
        JsonReader jr,
        boolean withPrerelease,
        String flavor,
        String packageName
    ) throws IOException {
        GitHubRelease release = new GitHubRelease();
        boolean invalid = false;
        boolean hasSchema = false;
        boolean hasVersionCode = false;
        boolean hasChannel = false;
        boolean hasAssets = false;
        Set<String> assetKeys = new HashSet<>();
        jr.beginObject();
        while (jr.peek() != JsonToken.END_OBJECT) {
            switch (jr.nextName()) {
                case "schemaVersion":
                    hasSchema = jr.nextInt() == 1;
                    if (!hasSchema) invalid = true;
                    break;
                case "versionName":
                    release.versionCode = jr.nextString();
                    break;
                case "versionCode":
                    release.releaseVersionCode = jr.nextLong();
                    hasVersionCode = release.releaseVersionCode > 0L;
                    if (!hasVersionCode) invalid = true;
                    break;
                case "channel":
                    String channel = jr.nextString();
                    if (!"stable".equalsIgnoreCase(channel)
                        && !"beta".equalsIgnoreCase(channel)
                        && !"alpha".equalsIgnoreCase(channel)
                        && !"rc".equalsIgnoreCase(channel)) {
                        invalid = true;
                    }
                    release.beta = !"stable".equalsIgnoreCase(channel);
                    hasChannel = true;
                    if (release.beta && !withPrerelease) invalid = true;
                    break;
                case "notes":
                case "releaseNotes":
                    release.body = jr.nextString();
                    break;
                case "assets":
                    hasAssets = true;
                    jr.beginArray();
                    while (jr.hasNext()) {
                        String assetId = null;
                        String assetFlavor = null;
                        String assetPackage = null;
                        String url = null;
                        String digest = null;
                        long minSdk = -1L;
                        long sizeBytes = -1L;
                        jr.beginObject();
                        while (jr.peek() != JsonToken.END_OBJECT) {
                            switch (jr.nextName()) {
                                case "id": assetId = jr.nextString(); break;
                                case "flavor": assetFlavor = jr.nextString(); break;
                                case "packageName": assetPackage = jr.nextString(); break;
                                case "url": url = jr.nextString(); break;
                                case "sha256": digest = jr.nextString(); break;
                                case "minSdk": minSdk = jr.nextLong(); break;
                                case "sizeBytes": sizeBytes = jr.nextLong(); break;
                                default: jr.skipValue(); break;
                            }
                        }
                        jr.endObject();
                        String identity = String.valueOf(assetId) + "|" + assetFlavor + "|" + assetPackage + "|" + minSdk;
                        boolean validAsset = assetId != null && !assetId.trim().isEmpty()
                            && assetFlavor != null && !assetFlavor.trim().isEmpty()
                            && assetPackage != null && !assetPackage.trim().isEmpty()
                            && minSdk > 0L
                            && sizeBytes > 0L
                            && sizeBytes <= MAX_UPDATE_SIZE_BYTES
                            && url != null && isAllowedUpdateUrl(url)
                            && digest != null && digest.matches("(?i)^[0-9a-f]{64}$")
                            && assetKeys.add(identity);
                        if (!validAsset) invalid = true;
                        if (release.downloadUrl == null && flavor.equals(assetFlavor)
                            && packageName.equals(assetPackage) && minSdk <= Build.VERSION.SDK_INT
                            && validAsset) {
                            release.downloadUrl = url;
                            release.sha256 = digest;
                            release.sizeBytes = sizeBytes;
                        }
                    }
                    jr.endArray();
                    break;
                default:
                    jr.skipValue();
                    break;
            }
        }
        jr.endObject();
        if (release.versionCode != null) {
            try {
                GitHubRelease.ParsedVersion.parse(release.versionCode, "Manifest");
            } catch (IllegalArgumentException ignored) {
                invalid = true;
            }
        }
        if (!hasSchema || !hasVersionCode || !hasChannel || !hasAssets || release.downloadUrl == null) invalid = true;
        return invalid || release.versionCode == null ? null : release;
    }

    static boolean isAllowedUpdateUrl(@NonNull String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (uri.getUserInfo() != null || uri.getFragment() != null) return false;
            if ("https".equalsIgnoreCase(scheme) && host != null) return true;
            if (!BuildConfig.DEBUG || !"http".equalsIgnoreCase(scheme) || host == null) return false;
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "10.0.2.2".equals(host)
                || "::1".equals(host);
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    @NonNull
    private static String releaseApiUrl() {
        if (!BuildConfig.UPDATE_ENDPOINT.isEmpty()) return BuildConfig.UPDATE_ENDPOINT;
        return RELEASE_API_URL;
    }

    private static AssetCandidate getAsset(JsonReader jr) throws IOException {
        String name = null;
        String url = null;
        String digest = null;
        jr.beginObject();
        while (jr.peek() != JsonToken.END_OBJECT) {
            String property = jr.nextName();
            if ("name".equals(property))
                name = jr.nextString();
            else if ("browser_download_url".equals(property))
                url = jr.nextString();
            else if ("digest".equals(property))
                digest = jr.nextString();
            else jr.skipValue();
        }
        jr.endObject();
        if (name == null || url == null) return null;
        return new AssetCandidate(name, url, digest);
    }

    private static final class AssetCandidate {
        private final String name;
        private final String url;
        private final String sha256;

        private AssetCandidate(String name, String url, String sha256) {
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
        }
    }

    static boolean matchesFlavorAsset(String assetName, String flavor) {
        return assetName != null && flavor != null && assetName.endsWith(".apk")
            && assetName.contains("-" + flavor + "-");
    }

    private void createDialog(GitHubRelease release) {
        String finalBody = release.body;
        String latestVersion = release.versionCode;
        boolean beta = release.beta;
        if (finalBody == null) return;
        finalBody = finalBody
            .replace("\r\n", "\n")//Remove ugly newline
            .replace("NReader " + latestVersion, "")//remove version header
            .replaceAll("(\\s*\n\\s*)+", "\n")//remove multiple newline
            .replaceAll("\\(.*\\)", "").trim();//remove things between ()
        LogUtility.d("Evaluated: " + finalBody);
        LogUtility.d("Creating dialog");
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        LogUtility.d("" + context);
        builder.setTitle(beta ? R.string.new_beta_version_found : R.string.new_version_found);
        builder.setIcon(R.drawable.ic_file);
        builder.setMessage(context.getString(R.string.update_version_format, BuildConfig.VERSION_NAME, latestVersion, finalBody));
        builder.setPositiveButton(R.string.install, (dialog, which) -> {
            if (Global.hasStoragePermission(context)) downloadVersion(latestVersion);
            else {
                latest = latestVersion;
                context.runOnUiThread(() -> context.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 2));
            }
        }).setNegativeButton(R.string.cancel, null);
        if (!BuildConfig.UPDATE_REPOSITORY.isEmpty()) {
            builder.setNeutralButton(R.string.github, (dialog, which) -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/" + BuildConfig.UPDATE_REPOSITORY + "/releases/latest"));
                context.startActivity(browserIntent);
            });
        }
        if (!context.isFinishing()) builder.show();
    }

    private void downloadVersion(String latestVersion) {
        if (hasActiveDownload() || downloadUrl == null) return;
        if (Global.UPDATEFOLDER == null) Global.initStorage(context);
        if (Global.UPDATEFOLDER == null || (!Global.UPDATEFOLDER.isDirectory() && !Global.UPDATEFOLDER.mkdirs())) {
            stateStore.runIfDownloadable(latestVersion, () ->
                saveState(UpdateState.error(latestVersion, null, "download", System.currentTimeMillis())));
            return;
        }
        String flavorSuffix = BuildConfig.FLAVOR.equals("minApi26") ? "-minApi26" : "-minApi28";
        final File f = new File(Global.UPDATEFOLDER,
            "NReader-" + latestVersion + flavorSuffix + "-" + UUID.randomUUID() + ".apk");
        final File partial = new File(f.getAbsolutePath() + ".part");
        final String expectedSha256 = metadataStore.getSelectedSha256();
        final long expectedSize = metadataStore.getSelectedSize();
        final Call attempt = updateClient.newCall(new Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME)
            .build());
        boolean started = stateStore.runIfDownloadable(latestVersion, () -> {
            downloadPath = partial.getAbsolutePath();
            downloadCall = attempt;
            saveState(UpdateState.downloading(latestVersion, downloadPath, 0L, expectedSize, System.currentTimeMillis()));
        });
        if (!started) return;
        attempt.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                failDownload(partial, latestVersion, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Unable to download update: HTTP " + response.code());
                    }
                    if (!stateStore.runIfDownloading(partial.getAbsolutePath(), () -> {})) return;
                    long expectedLength = response.body().contentLength();
                    if (expectedLength > MAX_UPDATE_SIZE_BYTES
                        || (expectedSize > MAX_UPDATE_SIZE_BYTES)) {
                        throw new IOException("Update package exceeds the maximum allowed size");
                    }
                    long writtenLength = 0L;
                    try (FileOutputStream stream = new FileOutputStream(partial);
                         InputStream stream1 = response.body().byteStream()) {
                        int read;
                        byte[] bytes = new byte[1024];
                        while ((read = stream1.read(bytes)) != -1) {
                            stream.write(bytes, 0, read);
                            writtenLength += read;
                            if (writtenLength > MAX_UPDATE_SIZE_BYTES) {
                                throw new IOException("Update package exceeds the maximum allowed size");
                            }
                        }
                        stream.flush();
                    }
                    if (expectedLength >= 0L && writtenLength != expectedLength) {
                        throw new IOException("Downloaded update length does not match response");
                    }
                    if (expectedSize > 0L && writtenLength != expectedSize) {
                        throw new IOException("Downloaded update size does not match release metadata");
                    }
                    if (!UpdateFileVerifier.matchesSha256(partial, expectedSha256)) {
                        throw new IOException("Downloaded update checksum does not match release metadata");
                    }
                    boolean owned = stateStore.runIfDownloading(partial.getAbsolutePath(), () -> {
                        if (!partial.renameTo(f)) throw new IllegalStateException("Unable to finalize downloaded update");
                        saveState(UpdateState.readyToInstall(latestVersion, f.getAbsolutePath(), f.length(), System.currentTimeMillis()));
                    });
                    if (owned && listener == null) context.runOnUiThread(() -> {
                        UpdateState state = stateStore.load();
                        if (!context.isFinishing() && !context.isDestroyed()
                            && state.isReadyToInstall() && f.getAbsolutePath().equals(state.getFilePath())) {
                            installApp(f);
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    failDownload(partial, latestVersion, e);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                }
            }
        });
    }

    private void failDownload(File partial, String version, Exception exception) {
        boolean owned = stateStore.runIfDownloading(partial.getAbsolutePath(), () ->
            saveState(UpdateState.error(version, partial.getAbsolutePath(), "download", System.currentTimeMillis())));
        //noinspection ResultOfMethodCallIgnored
        partial.delete();
        if (owned) notifyDownloadFailure(exception);
    }

    private void notifyDownloadFailure(Exception exception) {
        context.runOnUiThread(() -> {
            LogUtility.e("Update download failed: " + exception.getClass().getSimpleName());
            Toast.makeText(context, R.string.download_update_failed, Toast.LENGTH_LONG).show();
        });
    }

    private boolean installApp(File f) {
        if (!INSTALL_VALIDATION_ACTIVE.compareAndSet(false, true)) return false;
        long revision = stateStore.revision();
        INSTALL_VALIDATOR.execute(() -> {
            try {
                validateInstall(f);
                context.runOnUiThread(() -> {
                    try {
                        runIfReadyForInstall(revision, f, () -> launchInstaller(f));
                    } finally {
                        INSTALL_VALIDATION_ACTIVE.set(false);
                    }
                });
            } catch (IOException | RuntimeException exception) {
                context.runOnUiThread(() -> {
                    try {
                        runIfReadyForInstall(revision, f, () -> {
                            saveState(UpdateState.error(null, f.getAbsolutePath(), "install_validation", System.currentTimeMillis()));
                            Toast.makeText(context, R.string.download_update_failed, Toast.LENGTH_LONG).show();
                        });
                    } finally {
                        INSTALL_VALIDATION_ACTIVE.set(false);
                    }
                });
            }
        });
        return true;
    }

    private void runIfReadyForInstall(long revision, File file, Runnable action) {
        stateStore.runIfUnchanged(revision, () -> {
            UpdateState state = stateStore.load();
            if (!cancelled && !context.isFinishing() && !context.isDestroyed()
                && context.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                && state.isReadyToInstall()
                && file.getAbsolutePath().equals(state.getFilePath())) action.run();
        });
    }

    private void validateInstall(File f) throws IOException {
        String expectedSha256 = metadataStore.getSelectedSha256();
        if (!isValidSha256(expectedSha256)
            || !UpdateFileVerifier.matchesSha256(f, expectedSha256)) {
            throw new IOException("Cached update checksum does not match release metadata");
        }
        int signingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), signingFlags);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) {
            throw new IOException("Update package name does not match the installed application");
        }
        long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? archive.getLongVersionCode()
            : archive.versionCode;
        if (archiveVersion <= BuildConfig.VERSION_CODE) {
            throw new IOException("Update package version is not newer than the installed application");
        }
        long expectedReleaseVersionCode = metadataStore.getSelectedVersionCode();
        if (expectedReleaseVersionCode > 0L && archiveVersion != expectedReleaseVersionCode) {
            throw new IOException("Update package version does not match release metadata");
        }
        if (!hasMatchingSigner(archive)) {
            throw new IOException("Update package signer does not match the installed application");
        }
    }

    private void launchInstaller(File f) {
        try {
            UpdateState savedState = stateStore.load();
            String version = savedState.getVersion() == null ? f.getName() : savedState.getVersion();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
                Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName())
                );
                context.startActivity(settingsIntent);
                return;
            }
            saveState(UpdateState.installing(version, f.getAbsolutePath(), System.currentTimeMillis()));
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", f);
            @SuppressLint("RequestInstallPackagesPolicy") Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(apkUri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (installLauncher != null) installLauncher.launch(intent);
            else context.startActivityForResult(intent, INSTALL_REQUEST_CODE);
        } catch (IllegalArgumentException | SecurityException | ActivityNotFoundException exception) {
            saveState(UpdateState.error(null, f.getAbsolutePath(), "install_validation", System.currentTimeMillis()));
            context.runOnUiThread(() -> Toast.makeText(context, R.string.download_update_failed, Toast.LENGTH_LONG).show());

        }
    }

    private boolean hasMatchingSigner(@NonNull PackageInfo archive) {
        PackageInfo installed;
        try {
            installed = context.getPackageManager().getPackageInfo(
                context.getPackageName(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES
            );
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (installed.signingInfo == null || archive.signingInfo == null) return false;
            Signature[] installedSigners = installed.signingInfo.hasMultipleSigners()
                ? installed.signingInfo.getApkContentsSigners()
                : installed.signingInfo.getSigningCertificateHistory();
            Signature[] archiveSigners = archive.signingInfo.hasMultipleSigners()
                ? archive.signingInfo.getApkContentsSigners()
                : archive.signingInfo.getSigningCertificateHistory();
            if (installed.signingInfo.hasMultipleSigners() || archive.signingInfo.hasMultipleSigners()) {
                return new HashSet<>(Arrays.asList(installed.signingInfo.getApkContentsSigners()))
                    .equals(new HashSet<>(Arrays.asList(archive.signingInfo.getApkContentsSigners())));
            }
            return intersects(installedSigners, archiveSigners);
        }
        return installed.signatures != null
            && archive.signatures != null
            && intersects(installed.signatures, archive.signatures);
    }

    private static boolean intersects(@NonNull Signature[] left, @NonNull Signature[] right) {
        HashSet<Signature> values = new HashSet<>(Arrays.asList(left));
        for (Signature signature : right) {
            if (values.contains(signature)) return true;
        }
        return false;
    }


    public static class GitHubRelease {
        private final static String regex = "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(pre\\d+|(?:alpha|beta|rc)\\.\\d+))?$";
        String versionCode, body, downloadUrl, sha256, manifestUrl;
        long releaseVersionCode = -1L;
        long sizeBytes = -1L;
        boolean beta;

        @Nullable public String getVersionCode() { return versionCode; }
        @Nullable public String getBody() { return body; }
        @Nullable public String getDownloadUrl() { return downloadUrl; }
        @Nullable public String getSha256() { return sha256; }
        public long getSizeBytes() { return sizeBytes; }
        public long getReleaseVersionCode() { return releaseVersionCode; }
        public boolean isBeta() { return beta; }

        static boolean isVersionAtLeast(String candidate, String current) {
            try {
                return ParsedVersion.parse(candidate, "Candidate").compareTo(
                    ParsedVersion.parse(current, "Current")) >= 0;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }

        public boolean isNewerThenVersion(String currentVersion) throws IllegalArgumentException {
            ParsedVersion current = ParsedVersion.parse(currentVersion, "Current");
            ParsedVersion newer = ParsedVersion.parse(versionCode, "New");
            return newer.compareTo(current) > 0;
        }

        public boolean isNewerThanInstalled(String currentVersion, long currentVersionCode) {
            if (releaseVersionCode > 0L) return releaseVersionCode > currentVersionCode;
            return isNewerThenVersion(currentVersion);
        }

        private static String normalizeVersion(String value) {
            String normalized = value == null ? "" : value.trim();
            normalized = normalized.replaceFirst("(?i)-(debug|relwithdebinfo)$", "");
            return normalized.startsWith("v") ? normalized.substring(1) : normalized;
        }

        static final class ParsedVersion implements Comparable<ParsedVersion> {
            private final int major;
            private final int minor;
            private final int patch;
            private final int qualifierRank;
            private final int qualifierSequence;

            private ParsedVersion(int major, int minor, int patch, int qualifierRank, int qualifierSequence) {
                this.major = major;
                this.minor = minor;
                this.patch = patch;
                this.qualifierRank = qualifierRank;
                this.qualifierSequence = qualifierSequence;
            }

            private static ParsedVersion parse(String value, String label) {
                value = normalizeVersion(value);
                Matcher matcher = Pattern.compile(regex).matcher(value);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException(label + " Version not in format");
                }
                String qualifier = matcher.group(4);
                int qualifierRank = 4;
                int qualifierSequence = Integer.MAX_VALUE;
                if (qualifier != null) {
                    if (qualifier.startsWith("pre")) {
                        qualifierRank = 0;
                        qualifierSequence = Integer.parseInt(qualifier.substring(3));
                    } else {
                        String name = qualifier.substring(0, qualifier.indexOf('.'));
                        qualifierSequence = Integer.parseInt(qualifier.substring(qualifier.indexOf('.') + 1));
                        qualifierRank = name.equals("alpha") ? 1 : name.equals("beta") ? 2 : 3;
                    }
                }
                return new ParsedVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    qualifierRank,
                    qualifierSequence
                );
            }

            @Override
            public int compareTo(ParsedVersion other) {
                int result = Integer.compare(major, other.major);
                if (result != 0) return result;
                result = Integer.compare(minor, other.minor);
                if (result != 0) return result;
                result = Integer.compare(patch, other.patch);
                if (result != 0) return result;
                result = Integer.compare(qualifierRank, other.qualifierRank);
                if (result != 0) return result;
                return Integer.compare(qualifierSequence, other.qualifierSequence);
            }
        }

    }
}
