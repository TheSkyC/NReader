package com.theskyc.nreader.async;

import android.content.Context;

import com.theskyc.nreader.api.InspectorV3;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.api.enums.SpecialTagIds;
import com.theskyc.nreader.api.local.LocalGallery;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.LogUtility;

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
            //noinspection CallToThreadRun
            inspector.run();//it is run, not start
            if (inspector.getGalleries() == null || inspector.getGalleries().isEmpty())
                continue;
            Gallery g = (Gallery) inspector.getGalleries().get(0);
            try (FileWriter writer = new FileWriter(new File(lg.getDirectory(), ".nomedia"))) {
                g.jsonWrite(writer);
            } catch (IOException e) {
                LogUtility.e("Error saving metadata", e);
            }
        }
    }
}
