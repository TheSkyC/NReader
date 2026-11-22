package com.theskyc.nreader;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;

public final class TagRecoveryProcess {
    private TagRecoveryProcess() {}

    public static String tokenForPhase(String phase) {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(phase.equals(arguments.getString("recoveryPhase")));
        String token = arguments.getString("recoveryToken");
        assertTrue(token != null && token.matches("[a-f0-9]{16}"));
        return token;
    }

    public static void awaitExternalStop(String token) {
        Log.i("TagRecoveryProcessDeath", "READY:" + token + ":" + Process.myPid());
        SystemClock.sleep(180_000);
        fail("Host did not stop the process after the recovery marker");
    }
}
