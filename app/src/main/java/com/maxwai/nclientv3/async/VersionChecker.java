// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.JsonReader;
import android.util.JsonToken;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.async.update.UpdateState;
import com.maxwai.nclientv3.async.update.UpdateStateStore;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class VersionChecker {
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
    private final OkHttpClient updateClient = new OkHttpClient.Builder().build();
    @Nullable private final Listener listener;
    @Nullable private GitHubRelease latestRelease;
    private String downloadUrl;
    @Nullable private Call metadataCall;
    @Nullable private Call downloadCall;

    public VersionChecker(AppCompatActivity context, final boolean silent) {
        this(context, silent, null);
    }

    public VersionChecker(AppCompatActivity context, final boolean silent, @Nullable Listener listener) {
        boolean withPrerelease = Global.isEnableBeta();
        this.context = context;
        this.stateStore = new UpdateStateStore(context);
        this.metadataStore = new UpdateMetadataStore(context);
        this.listener = listener;
        if (BuildConfig.UPDATE_REPOSITORY.isEmpty()) return;
        if (latest != null && Global.hasStoragePermission(context)) {
            downloadVersion(latest);
            latest = null;
            return;
        }
        saveState(UpdateState.checking(System.currentTimeMillis()));
        LogUtility.d("ACTUAL VERSION: " + BuildConfig.VERSION_NAME);
        requestReleases(withPrerelease, silent, true);
    }

    private void requestReleases(boolean withPrerelease, boolean silent, boolean useConditionalRequest) {
        Request.Builder request = new Request.Builder()
            .url(RELEASE_API_URL)
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
                            long length = response.body().contentLength();
                            if (length > 2L * 1024L * 1024L) {
                                throw new IOException("Release metadata is too large");
                            }
                            json = response.body().string();
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
                    downloadUrl = release.downloadUrl;
                    GitHubRelease finalRelease = release;
                    context.runOnUiThread(() -> {
                        boolean newer;
                        try {
                            newer = finalRelease.isNewerThenVersion(BuildConfig.VERSION_NAME);
                        } catch (IllegalArgumentException ignored) {
                            newer = false;
                        }
                        if (downloadUrl == null || !newer) {
                            saveState(UpdateState.idle(System.currentTimeMillis()));
                            if (!silent)
                                Toast.makeText(context, R.string.no_updates_found, Toast.LENGTH_SHORT).show();
                        } else {
                            latestRelease = finalRelease;
                            metadataStore.setSelectedSha256(finalRelease.sha256);
                            saveState(UpdateState.available(finalRelease.versionCode, System.currentTimeMillis()));
                            if (listener == null) createDialog(finalRelease);
                            else context.runOnUiThread(() -> listener.onReleaseAvailable(finalRelease));
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    notifyRetrievalFailure(e, silent);
                }
            }
        });
    }

    private void notifyRetrievalFailure(Exception exception, boolean silent) {
        saveState(UpdateState.error(null, null, "metadata", System.currentTimeMillis()));
        context.runOnUiThread(() -> {
            LogUtility.e(exception.getLocalizedMessage(), exception);
            if (!silent)
                Toast.makeText(context, R.string.error_retrieving, Toast.LENGTH_SHORT).show();
        });
    }

    private void saveState(@NonNull UpdateState state) {
        stateStore.save(state);
        UpdateNotification.publish(context, state);
        if (listener != null) {
            context.runOnUiThread(() -> listener.onUpdateState(state));
        }
    }

    public void startDownload() {
        GitHubRelease release = latestRelease;
        if (release == null || release.versionCode == null) return;
        downloadVersion(release.versionCode);
    }

    public void cancel() {
        if (metadataCall != null) metadataCall.cancel();
        if (downloadCall != null) downloadCall.cancel();
    }

    public void cancelDownload() {
        if (downloadCall != null) downloadCall.cancel();
        UpdateState state = stateStore.load();
        if (state.getFilePath() != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(state.getFilePath()).delete();
        }
        saveState(UpdateState.idle(System.currentTimeMillis()));
        UpdateNotification.clear(context);
    }

    public void installReadyUpdate() {
        UpdateState state = UpdateStateRecovery.reconcile(context, stateStore.load());
        stateStore.save(state);
        if (!state.isReadyToInstall() || state.getFilePath() == null) return;
        File file = new File(state.getFilePath());
        if (!file.isFile()) {
            saveState(UpdateState.error(state.getVersion(), state.getFilePath(), "missing_file", System.currentTimeMillis()));
            return;
        }
        installApp(file);
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
                        if (release.downloadUrl != null) {
                            jr.skipValue();
                            continue;
                        }
                        AssetCandidate candidate = getAsset(jr, flavor);
                        if (release.downloadUrl == null && candidate != null) {
                            release.downloadUrl = candidate.url;
                            release.sha256 = candidate.sha256;
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

    private static AssetCandidate getAsset(JsonReader jr, String flavor) throws IOException {
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
        if (name == null || url == null || !matchesFlavorAsset(name, flavor)) return null;
        return new AssetCandidate(url, digest);
    }

    private static final class AssetCandidate {
        private final String url;
        private final String sha256;

        private AssetCandidate(String url, String sha256) {
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
        }).setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.github, (dialog, which) -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_RELEASE_URL));
                context.startActivity(browserIntent);
            });
        if (!context.isFinishing()) builder.show();
    }

    private void downloadVersion(String latestVersion) {
        String flavorSuffix = BuildConfig.FLAVOR.equals("minApi26") ? "-minApi26" : "-minApi28";
        final File f = new File(Global.UPDATEFOLDER,
            "NReader-" + latestVersion + flavorSuffix + "-release.apk");
        final File partial = new File(f.getAbsolutePath() + ".part");
        final String expectedSha256 = metadataStore.getSelectedSha256();
        if (f.exists()) {
            UpdateState savedState = stateStore.load();
            if (savedState.isReadyToInstall()
                && latestVersion.equals(savedState.getVersion())
                && f.getAbsolutePath().equals(savedState.getFilePath())) {
                installApp(f);
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        partial.delete();
        if (downloadUrl == null) return;
        LogUtility.d(f.getAbsolutePath());
        downloadCall = updateClient.newCall(new Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "NReader/" + BuildConfig.VERSION_NAME)
            .build());
        downloadCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (call.isCanceled() || call != downloadCall) return;
                notifyDownloadFailure(e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (call != downloadCall) {
                    response.close();
                    return;
                }
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Unable to download update: HTTP " + response.code());
                    }
                    context.getSharedPreferences("Settings", 0).edit().putBoolean("downloaded", false).apply();
                    if (Global.UPDATEFOLDER == null) {
                        Global.initStorage(context);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    Global.UPDATEFOLDER.mkdirs();
                    //noinspection ResultOfMethodCallIgnored
                    partial.createNewFile();
                    saveState(UpdateState.downloading(
                        latestVersion,
                        partial.getAbsolutePath(),
                        0L,
                        response.body().contentLength(),
                        System.currentTimeMillis()
                    ));
                    long expectedLength = response.body().contentLength();
                    long writtenLength = 0L;
                    try (FileOutputStream stream = new FileOutputStream(partial);
                         InputStream stream1 = response.body().byteStream()) {
                        int read;
                        byte[] bytes = new byte[1024];
                        while ((read = stream1.read(bytes)) != -1) {
                            stream.write(bytes, 0, read);
                            writtenLength += read;
                        }
                        stream.flush();
                    }
                    if (expectedLength >= 0L && writtenLength != expectedLength) {
                        throw new IOException("Downloaded update length does not match response");
                    }
                    if (!UpdateFileVerifier.matchesSha256(partial, expectedSha256)) {
                        throw new IOException("Downloaded update checksum does not match release metadata");
                    }
                    if (f.exists() && !f.delete()) {
                        throw new IOException("Unable to replace previous update");
                    }
                    if (!partial.renameTo(f)) {
                        throw new IOException("Unable to finalize downloaded update");
                    }
                    saveState(UpdateState.readyToInstall(
                        latestVersion,
                        f.getAbsolutePath(),
                        f.length(),
                        System.currentTimeMillis()
                    ));
                    installApp(f);
                } catch (IOException | RuntimeException e) {
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    saveState(UpdateState.error(
                        latestVersion,
                        f.getAbsolutePath(),
                        "download",
                        System.currentTimeMillis()
                    ));
                    notifyDownloadFailure(e);
                }
            }
        });
    }

    private void notifyDownloadFailure(Exception exception) {
        context.runOnUiThread(() -> {
            LogUtility.e(exception.getLocalizedMessage(), exception);
            Toast.makeText(context, R.string.download_update_failed, Toast.LENGTH_LONG).show();
        });
    }

    private void installApp(File f) {
        try {
            if (!UpdateFileVerifier.matchesSha256(f, metadataStore.getSelectedSha256())) {
                throw new IOException("Cached update checksum does not match release metadata");
            }
            PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (archive == null || !context.getPackageName().equals(archive.packageName)) {
                throw new IOException("Update package name does not match the installed application");
            }
            long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? archive.getLongVersionCode()
                : archive.versionCode;
            if (archiveVersion <= BuildConfig.VERSION_CODE) {
                throw new IOException("Update package version is not newer than the installed application");
            }
            UpdateState savedState = stateStore.load();
            String version = savedState.getVersion() == null ? f.getName() : savedState.getVersion();
            saveState(UpdateState.installing(version, f.getAbsolutePath(), System.currentTimeMillis()));
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", f);
            @SuppressLint("RequestInstallPackagesPolicy") Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(apkUri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            saveState(UpdateState.error(null, f.getAbsolutePath(), "install_validation", System.currentTimeMillis()));
            context.runOnUiThread(() -> Toast.makeText(context, R.string.download_update_failed, Toast.LENGTH_LONG).show());

        }
    }


    public static class GitHubRelease {
        private final static String regex = "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(pre\\d+|(?:alpha|beta|rc)\\.\\d+))?$";
        String versionCode, body, downloadUrl, sha256;
        boolean beta;

        @Nullable public String getVersionCode() { return versionCode; }
        @Nullable public String getBody() { return body; }
        @Nullable public String getDownloadUrl() { return downloadUrl; }
        @Nullable public String getSha256() { return sha256; }
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
