package com.theskyc.nreader;

import androidx.work.impl.WorkManagerImpl;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.reflect.Field;

/** Restores the WorkManager delegate replaced by a test initializer. */
public final class WorkManagerTestScope implements TestRule {
    @Override public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                Field field = WorkManagerImpl.class.getDeclaredField("sDelegatedInstance");
                field.setAccessible(true);
                WorkManagerImpl previous = (WorkManagerImpl) field.get(null);
                try {
                    base.evaluate();
                } finally {
                    WorkManagerImpl.setDelegate(previous);
                }
            }
        };
    }
}
