package com.theskyc.nreader;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import android.app.Application;

import androidx.work.impl.WorkManagerImpl;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class WorkManagerTestScopeTest {
    @Rule public final WorkManagerTestScope workManagerScope = new WorkManagerTestScope();

    @Test public void restoresNullDelegate() throws Throwable {
        WorkManagerImpl.setDelegate(null);
        Field field = WorkManagerImpl.class.getDeclaredField("sDelegatedInstance");
        field.setAccessible(true);
        Statement test = new WorkManagerTestScope().apply(new Statement() {
            @Override public void evaluate() {
                WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
            }
        }, Description.EMPTY);
        test.evaluate();
        assertNull(field.get(null));
    }

    @Test public void restoresPreviousDelegateAfterSuccessAndFailure() throws Throwable {
        Application context = RuntimeEnvironment.getApplication();
        WorkManagerTestInitHelper.initializeTestWorkManager(context);
        WorkManagerImpl previous = WorkManagerImpl.getInstance(context);
        for (boolean failInside : new boolean[]{false, true}) {
            RuntimeException expected = new RuntimeException("Synthetic test failure");
            Statement test = new WorkManagerTestScope().apply(new Statement() {
                @Override public void evaluate() {
                    WorkManagerTestInitHelper.initializeTestWorkManager(context);
                    assertNotSame(previous, WorkManagerImpl.getInstance(context));
                    if (failInside) throw expected;
                }
            }, Description.EMPTY);
            try {
                test.evaluate();
                if (failInside) fail("Expected the test failure to propagate");
            } catch (RuntimeException error) {
                assertSame(expected, error);
            }
            assertSame(previous, WorkManagerImpl.getInstance(context));
        }
    }
}
