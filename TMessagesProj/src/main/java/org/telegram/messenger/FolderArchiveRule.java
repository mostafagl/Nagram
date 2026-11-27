package org.telegram.messenger;

/**
 * The part of "is this chat in this folder?" that depends only on the folder's explicit lists and the
 * chat's archive state. It is kept free of Android classes and settings so it can be unit tested;
 * {@link MessagesController.DialogFilter#includesDialog} asks it first and continues with the folder's
 * categories (contacts, groups, ...) only when it has no answer.
 */
public final class FolderArchiveRule {

    private FolderArchiveRule() {
    }

    /**
     * @param inNeverShow           the chat is in the folder's "excluded chats"
     * @param inAlwaysShow          the chat was added to the folder by hand
     * @param dialogFolderId        0 = main list, 1 = archive
     * @param excludeArchivedFlag   the folder's own "exclude archived chats" option
     * @param archiveHidesEverywhere Parvagram setting: an archived chat is in no folder
     * @return TRUE/FALSE when the chat is decidedly in/out of the folder, null when the folder's categories decide
     */
    public static Boolean decide(boolean inNeverShow, boolean inAlwaysShow, int dialogFolderId,
                                 boolean excludeArchivedFlag, boolean archiveHidesEverywhere) {
        final boolean archived = dialogFolderId != 0;
        if (inNeverShow) {
            return Boolean.FALSE;
        }
        if (archived && archiveHidesEverywhere) {
            // Before the "added by hand" check: those chats were the ones that stayed visible.
            return Boolean.FALSE;
        }
        if (inAlwaysShow) {
            return Boolean.TRUE;
        }
        if (archived && excludeArchivedFlag) {
            return Boolean.FALSE;
        }
        return null;
    }
}
