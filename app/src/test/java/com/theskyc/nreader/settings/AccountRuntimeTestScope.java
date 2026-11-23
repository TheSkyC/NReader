package com.theskyc.nreader.settings;

import java.lang.reflect.Field;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public final class AccountRuntimeTestScope implements TestRule {
    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                clearRuntime();
                try {
                    base.evaluate();
                } finally {
                    clearRuntime();
                }
            }
        };
    }

    private static void clearRuntime() throws Exception {
        AccountRuntime.awaitInitialization();
        field("repository").set(null, null);
        field("openError").set(null, null);
        field("initialization").set(null, null);
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = AccountRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
