package com.maxwai.nclientv2NG.async;

import android.content.Context;

import com.maxwai.nclientv2NG.api.InspectorV3;
import com.maxwai.nclientv2NG.api.components.Gallery;
import com.maxwai.nclientv2NG.api.enums.SpecialTagIds;
import com.maxwai.nclientv2NG.api.local.LocalGallery;
import com.maxwai.nclientv2NG.settings.Global;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class MetadataFetcher implements Runnable {
    private final Context context;

    public MetadataFetcher(Context context) {
        this.context = context;
    }

    @Override
    public void run() {
        File[] files = Global.DOWNLOADFOLDER.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            LocalGallery lg = new LocalGallery(f, false);
            if (lg.getId() == SpecialTagIds.INVALID_ID || lg.hasGalleryData()) continue;
            InspectorV3 inspector = InspectorV3.galleryInspector(context, lg.getId(), null);
            inspector.run();//it is run, not start
            if (inspector.getGalleries() == null || inspector.getGalleries().size() == 0)
                continue;
            Gallery g = (Gallery) inspector.getGalleries().get(0);
            try {
                FileWriter writer = new FileWriter(new File(lg.getDirectory(), ".nomedia"));
                g.jsonWrite(writer);
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
