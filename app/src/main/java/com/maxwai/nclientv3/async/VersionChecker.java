// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class VersionChecker {
    private static final String RELEASE_API_URL = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPOSITORY + "/releases";
    private static final String LATEST_RELEASE_URL = "https://github.com/" + BuildConfig.UPDATE_REPOSITORY + "/releases/latest";
    private static String latest = null;
    private final AppCompatActivity context;
    private String downloadUrl;

    public VersionChecker(AppCompatActivity context, final boolean silent) {
        boolean withPrerelease = Global.isEnableBeta();
        this.context = context;
        if (BuildConfig.UPDATE_REPOSITORY.isEmpty()) return;
        if (latest != null && Global.hasStoragePermission(context)) {
            downloadVersion(latest);
            latest = null;
            return;
        }
        LogUtility.d("ACTUAL VERSION: " + BuildConfig.VERSION_NAME);
        Global.getClient(context).newCall(new Request.Builder().url(RELEASE_API_URL).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                context.runOnUiThread(() -> {
                    LogUtility.e(e.getLocalizedMessage(), e);
                    if (!silent)
                        Toast.makeText(context, R.string.error_retrieving, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                GitHubRelease release;
                try (JsonReader jr = new JsonReader(response.body().charStream())) {
                    release = parseVersionJson(jr, withPrerelease);
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
                    } catch (IllegalStateException ignored) {
                        newer = false;
                    }
                    if (downloadUrl == null || !newer) {
                        if (!silent)
                            Toast.makeText(context, R.string.no_updates_found, Toast.LENGTH_SHORT).show();
                    } else {
                        LogUtility.d("Executing false");
                        createDialog(finalRelease);
                    }
                });
            }
        });
    }

    private static GitHubRelease parseVersionJson(JsonReader jr, boolean withPrerelease) throws IOException {
        try {
            GitHubRelease release;
            jr.beginArray();
            while (jr.hasNext()) {
                release = parseVersion(jr, withPrerelease);
                if (release != null)
                    return release;
            }
        } catch (IllegalStateException ignore) {
        }
        return null;
    }

    private static GitHubRelease parseVersion(JsonReader jr, boolean withPrerelease) throws IOException {
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
                        release.downloadUrl = getDownloadUrl(jr);
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

    private static String getDownloadUrl(JsonReader jr) throws IOException {
        String name = null;
        String url = null;
        jr.beginObject();
        while (jr.peek() != JsonToken.END_OBJECT) {
            String property = jr.nextName();
            if ("name".equals(property))
                name = jr.nextString();
            else if ("browser_download_url".equals(property))
                url = jr.nextString();
            else jr.skipValue();
        }
        jr.endObject();
        if (name == null || url == null || !name.endsWith(".apk")) return null;
        boolean minApi26 = name.contains("-minApi26-");
        if (BuildConfig.FLAVOR.equals("minApi26") != minApi26) return null;
        return url;
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
        if (f.exists()) {
            if (context.getSharedPreferences("Settings", 0).getBoolean("downloaded", false)) {
                installApp(f);
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        if (downloadUrl == null) return;
        LogUtility.d(f.getAbsolutePath());
        Global.getClient(context).newCall(new Request.Builder().url(downloadUrl).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                context.runOnUiThread(() -> Toast.makeText(context, R.string.download_update_failed, Toast.LENGTH_LONG).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                context.getSharedPreferences("Settings", 0).edit().putBoolean("downloaded", false).apply();
                if (Global.UPDATEFOLDER == null) {
                    Global.initStorage(context);
                }
                //noinspection ResultOfMethodCallIgnored
                Global.UPDATEFOLDER.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                f.createNewFile();
                try (FileOutputStream stream = new FileOutputStream(f);
                     InputStream stream1 = response.body().byteStream()) {
                    int read;
                    byte[] bytes = new byte[1024];
                    while ((read = stream1.read(bytes)) != -1) {
                        stream.write(bytes, 0, read);
                    }
                    stream.flush();
                }
                context.getSharedPreferences("Settings", 0).edit().putBoolean("downloaded", true).apply();
                installApp(f);
            }
        });
    }

    private void installApp(File f) {
        try {
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", f);
            @SuppressLint("RequestInstallPackagesPolicy") Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(apkUri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        } catch (IllegalArgumentException ignore) {
            context.runOnUiThread(() -> Toast.makeText(context, context.getString(R.string.downloaded_update_at, f.getAbsolutePath()), Toast.LENGTH_SHORT).show());

        }
    }


    public static class GitHubRelease {
        private final static String regex = "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(pre\\d+|(?:alpha|beta|rc)\\.\\d+))?$";
        String versionCode, body, downloadUrl;
        boolean beta;

        public boolean isNewerThenVersion(String currentVersion) throws IllegalArgumentException {
            ParsedVersion current = ParsedVersion.parse(currentVersion, "Current");
            ParsedVersion newer = ParsedVersion.parse(versionCode, "New");
            return newer.compareTo(current) > 0;
        }

        private static String normalizeVersion(String value) {
            return value.startsWith("v") ? value.substring(1) : value;
        }

        private static final class ParsedVersion implements Comparable<ParsedVersion> {
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
