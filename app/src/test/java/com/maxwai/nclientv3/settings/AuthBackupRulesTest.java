package com.maxwai.nclientv3.settings;

import android.app.Application;
import android.content.res.XmlResourceParser;

import com.maxwai.nclientv3.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AuthBackupRulesTest {
    @Test public void backupRulesExcludeAuthenticationFromAllBackupModes() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Set<String> exclusions = new HashSet<>();
        try (XmlResourceParser xml = app.getResources().getXml(R.xml.backup_content)) {
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event == XmlPullParser.START_TAG && "exclude".equals(xml.getName())
                    && "sharedpref".equals(xml.getAttributeValue(null, "domain")))
                    exclusions.add(xml.getAttributeValue(null, "path"));
            }
        }
        assertTrue(exclusions.contains("Auth.xml"));
        assertTrue(exclusions.contains("Login.xml"));

        Set<String> modernExclusions = new HashSet<>();
        String mode = null;
        try (XmlResourceParser xml = app.getResources().getXml(R.xml.data_extraction_rules)) {
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event == XmlPullParser.START_TAG) {
                    if ("cloud-backup".equals(xml.getName()) || "device-transfer".equals(xml.getName()))
                        mode = xml.getName();
                    else if ("exclude".equals(xml.getName()) && "sharedpref".equals(xml.getAttributeValue(null, "domain")))
                        modernExclusions.add(mode + ":" + xml.getAttributeValue(null, "path"));
                } else if (event == XmlPullParser.END_TAG && xml.getName().equals(mode)) mode = null;
            }
        }
        for (String section : new String[]{"cloud-backup", "device-transfer"}) {
            assertTrue(modernExclusions.contains(section + ":Auth.xml"));
            assertTrue(modernExclusions.contains(section + ":Login.xml"));
        }
    }
}
