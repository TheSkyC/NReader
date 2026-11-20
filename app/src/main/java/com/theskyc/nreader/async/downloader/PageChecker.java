package com.theskyc.nreader.async.downloader;

import com.theskyc.nreader.utility.LogUtility;

import java.io.IOException;

public class PageChecker extends Thread {
    @Override
    public void run() {
        for (GalleryDownloaderV2 g : DownloadQueue.getDownloaders())
            if (g.hasData()) {
                try {
                    g.initDownload();
                } catch (IOException exception) {
                    LogUtility.e("Unable to inspect download pages", exception);
                }
            }
    }
}
