package app.breezescreen.tv;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebUrlPolicyTest {
    @Test public void acceptsOrdinaryWebUrls() {
        assertTrue(WebUrlPolicy.isSupportedWebUrl("https://www.douyin.com/"));
        assertTrue(WebUrlPolicy.isSupportedWebUrl("http://example.test/path"));
    }

    @Test public void rejectsNonWebSchemesAndBlankValues() {
        assertFalse(WebUrlPolicy.isSupportedWebUrl("intent://settings"));
        assertFalse(WebUrlPolicy.isSupportedWebUrl("file:///data/local/tmp"));
        assertFalse(WebUrlPolicy.isSupportedWebUrl(""));
        assertFalse(WebUrlPolicy.isSupportedWebUrl(null));
    }
}
