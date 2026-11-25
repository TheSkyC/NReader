// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async;

import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.Nullable;

import com.theskyc.nreader.BuildConfig;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

final class UpdateFeed {
    private UpdateFeed() { }

    @Nullable static String url() {
        if (!BuildConfig.UPDATE_ENDPOINT.isEmpty() || BuildConfig.UPDATE_REPOSITORY.isEmpty()) return null;
        return "https://raw.githubusercontent.com/" + BuildConfig.UPDATE_REPOSITORY + "/main/feed/app-updates.json";
    }

    @Nullable static VersionChecker.GitHubRelease parse(String json, boolean includeBeta, String flavor, String packageName)
        throws IOException {
        List<VersionChecker.GitHubRelease> candidates = new ArrayList<>();
        boolean schema = false;
        boolean stableSeen = false;
        boolean betaSeen = false;
        boolean releaseSeen = false;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("schemaVersion".equals(name)) {
                    if (schema || reader.nextInt() != 1) throw new IOException("Invalid update feed schema");
                    schema = true;
                } else if ("stable".equals(name) || "beta".equals(name)) {
                    boolean beta = "beta".equals(name);
                    if (beta ? betaSeen : stableSeen) throw new IOException("Duplicate update channel");
                    if (beta) betaSeen = true; else stableSeen = true;
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                        continue;
                    }
                    VersionChecker.GitHubRelease release = VersionChecker.parseManifestJson(reader, true, flavor, packageName);
                    if (release == null || release.beta != beta) throw new IOException("Invalid update channel manifest");
                    releaseSeen = true;
                    if (!beta || includeBeta) candidates.add(release);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (!schema || !stableSeen || !betaSeen || !releaseSeen || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Incomplete update feed");
            }
        } catch (IllegalStateException exception) {
            throw new IOException("Invalid update feed", exception);
        }
        return VersionChecker.selectNewestCompatible(candidates);
    }
}
