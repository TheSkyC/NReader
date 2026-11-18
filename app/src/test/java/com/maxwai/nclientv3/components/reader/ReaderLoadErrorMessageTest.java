// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.reader;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.utility.network.NetworkUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class ReaderLoadErrorMessageTest {
    @Test
    public void usesOfflineMessageWithoutValidatedNetwork() {
        NetworkUtil.setType(NetworkUtil.ConnectionType.UNKNOWN);

        assertEquals(R.string.reader_page_load_offline, ReaderLoadErrorMessage.getMessageResId());
    }

    @Test
    public void usesGenericMessageWhenNetworkIsValidated() {
        NetworkUtil.setType(NetworkUtil.ConnectionType.WIFI);

        assertEquals(R.string.reader_page_load_failed, ReaderLoadErrorMessage.getMessageResId());
        NetworkUtil.setType(NetworkUtil.ConnectionType.UNKNOWN);
    }
}
