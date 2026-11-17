package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FolderArchiveRuleTest {
    private static final int MAIN = 0, ARCHIVE = 1;

    private static Boolean decide(boolean never, boolean always, int folderId, boolean excludeFlag, boolean hides) {
        return FolderArchiveRule.decide(never, always, folderId, excludeFlag, hides);
    }

    @Test public void archivedChatAddedByHandIsHiddenWhenSettingIsOn() {
        // The reported bug: it stayed visible in the folder it was added to.
        assertEquals(Boolean.FALSE, decide(false, true, ARCHIVE, false, true));
    }

    @Test public void archivedChatInCategoryFolderIsHiddenWhenSettingIsOn() {
        // Contacts folder: no explicit lists, "exclude archived" not set.
        assertEquals(Boolean.FALSE, decide(false, false, ARCHIVE, false, true));
    }

    @Test public void upstreamBehaviorIsKeptWhenSettingIsOff() {
        assertEquals(Boolean.TRUE, decide(false, true, ARCHIVE, false, false));
        assertNull(decide(false, false, ARCHIVE, false, false));
        assertEquals(Boolean.FALSE, decide(false, false, ARCHIVE, true, false));
    }

    @Test public void unarchivedChatsAreNotAffected() {
        assertEquals(Boolean.TRUE, decide(false, true, MAIN, false, true));
        assertEquals(Boolean.TRUE, decide(false, true, MAIN, true, true));
        assertNull(decide(false, false, MAIN, false, true));
        assertNull(decide(false, false, MAIN, true, true));
    }

    @Test public void excludedChatsStayExcludedEvenIfAlsoAddedByHand() {
        assertEquals(Boolean.FALSE, decide(true, true, MAIN, false, false));
        assertEquals(Boolean.FALSE, decide(true, true, MAIN, false, true));
    }
}
