package com.theskyc.nreader;

import android.app.Application;
import android.content.Context;

import androidx.test.runner.AndroidJUnitRunner;

public final class TagBenchmarkRunner extends AndroidJUnitRunner {
    @Override
    public Application newApplication(ClassLoader loader, String className, Context context)
        throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return super.newApplication(loader, Application.class.getName(), context);
    }
}
