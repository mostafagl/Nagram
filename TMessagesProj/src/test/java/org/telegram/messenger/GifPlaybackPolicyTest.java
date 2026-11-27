package org.telegram.messenger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class GifPlaybackPolicyTest {
    private TLRPC.Document document(String mime, boolean animated) {
        TLRPC.Document doc = new TLRPC.TL_document();
        doc.mime_type = mime;
        TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
        video.w = 600;
        video.h = 338;
        doc.attributes.add(video);
        if (animated) doc.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        return doc;
    }

    @Test public void rawGifNeverEntersVideoPlayerEvenWithVideoPreference() {
        TLRPC.Document raw = document("image/gif", true);
        assertTrue(MessageObject.isGifDocument(raw));
        assertFalse(MessageObject.shouldUseVideoPlayerForGif(raw, true));
        assertFalse(MessageObject.shouldUseVideoPlayerForGif(raw, false));
    }

    @Test public void animatedMp4UsesVideoOnlyWhenEnabled() {
        TLRPC.Document mp4 = document("video/mp4", true);
        assertTrue(MessageObject.shouldUseVideoPlayerForGif(mp4, true));
        assertFalse(MessageObject.shouldUseVideoPlayerForGif(mp4, false));
        assertFalse(MessageObject.shouldUseVideoPlayerForGif(document("video/mp4", false), true));
        assertFalse(MessageObject.shouldUseVideoPlayerForGif(null, true));
    }
}
