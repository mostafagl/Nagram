package xyz.nextalone.nagram.history;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.Index;

/**
 * The last content the client held for a message before it was deleted.
 * Only stored when the content had actually been received beforehand.
 */
@Entity
public class DeletedMessageItem {

    @Id
    public long id;

    public int account;
    @Index
    public long dialogId;
    public long messageId;
    public long fromId;

    /** Whether the deleted message was sent by the local user. */
    public boolean outgoing;

    /** Message text or media caption that was held before deletion. */
    public String text;

    /** A short, stable description of the media ("", "photo", "voice", ...). */
    public String mediaType;

    /** Local path of the media file if it was already cached, else "". */
    public String mediaPath;

    /** Telegram's message date in seconds (when the message was sent). */
    public long originalDate;

    /** Local wall-clock time (ms) when the client observed the deletion. */
    public long observedAt;

    public DeletedMessageItem() {
    }
}
