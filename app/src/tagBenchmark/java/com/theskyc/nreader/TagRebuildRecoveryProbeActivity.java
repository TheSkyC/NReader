package com.theskyc.nreader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.theskyc.nreader.async.database.TagTranslationCatalog;
import com.theskyc.nreader.settings.Database;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class TagRebuildRecoveryProbeActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String token = getIntent().getStringExtra("token");
        SharedPreferences probe = getSharedPreferences("tag-rebuild-process-probe", 0);
        String workId = probe.getString("workId", "");
        if (token == null || !token.matches("[a-f0-9]{16}") || !token.equals(probe.getString("token", ""))) {
            finish();
            return;
        }
        new Thread(() -> observe(token, workId), "tag-rebuild-probe").start();
    }

    private void observe(String token, String workId) {
        try {
            SQLiteDatabase database = Database.getDatabase();
            UUID id = UUID.fromString(workId);
            long deadline = SystemClock.elapsedRealtime() + 120_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                WorkInfo work = WorkManager.getInstance(this).getWorkInfoById(id).get(10, TimeUnit.SECONDS);
                if (work != null && work.getState() == WorkInfo.State.SUCCEEDED) {
                    boolean valid = !TagTranslationCatalog.isKeyRebuildPending(database)
                        && DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagTranslations"
                            + " WHERE locale='ja' AND displayName='name' AND displayKey='name'", null) == 201
                        && DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagTranslationAliases"
                            + " WHERE idTag=1 AND locale='ja' AND alias='alias' AND aliasKey='alias' AND aliasOrder=0", null) == 1
                        && DatabaseUtils.longForQuery(database, "SELECT revision FROM TagTranslationState WHERE locale='ja'", null) == 7
                        && DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM TagRules WHERE idTag=700001 AND status=1", null) == 1
                        && DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM AccountTagBlacklist WHERE idTag=700001", null) == 1;
                    Log.i("TagRebuildProcessProbe", (valid ? "PASS:" : "FAIL:") + token + ":" + Process.myPid() + ":" + id);
                    runOnUiThread(this::finish);
                    return;
                }
                SystemClock.sleep(100);
            }
            Log.e("TagRebuildProcessProbe", "TIMEOUT:" + token + ":" + Process.myPid());
        } catch (Exception error) {
            Log.e("TagRebuildProcessProbe", "ERROR:" + token + ":" + Process.myPid(), error);
        }
        runOnUiThread(this::finish);
    }
}
