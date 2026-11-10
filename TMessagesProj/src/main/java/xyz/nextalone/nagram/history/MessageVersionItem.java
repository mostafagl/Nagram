package xyz.nextalone.nagram.history;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.Index;

/**
 * One captured version of a message. Several of these with the same
 * (account, dialogId, messageId) form the edit history of a single message,
 * ordered by {@link #version}. Versions are never overwritten.
 */
@Entity
public class MessageVersionItem {

    @Id
    public long id;

    public int account;
    @Index
    public long dialogId;
    public long messageId;
    public long fromId;

    /** 1-based ordinal assigned when the version is first recorded. */
    public int version;

    /** Message text or media caption at the time this version was observed. */
    public String text;

    /** A short, stable description of the media ("", "photo", "voice", ...). */
    public String mediaType;

    /** Telegram's edit_date for this version in seconds, or 0 when unknown. */
    public long editDate;

    /** Local wall-clock time (ms) when the client recorded this version. */
    public long observedAt;

    /** Hash of the observable content, used to avoid duplicate versions. */
    public long contentHash;

    public MessageVersionItem() {
    }
}
