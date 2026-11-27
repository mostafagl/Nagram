package tw.nekomimi.nekogram.helpers;

import android.graphics.Rect;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;

/**
 * "Hold and drag": after a long press opens a message's menu, the same finger can slide onto an
 * item and lift to choose it, without a second tap. The gesture keeps going to the chat window
 * (Android sends the rest of a gesture to the window it started in), so the chat view hands its
 * touch events here while the menu is open.
 *
 * Lifting the finger without having slid away chooses nothing, so an ordinary long press still
 * just opens the menu.
 */
public final class MenuDragSelect {

    private boolean active;
    private boolean dragging;
    private float startX = Float.NaN;
    private float startY;
    private ActionBarMenuSubItem hovered;
    private boolean childrenCancelled;
    private final int[] location = new int[2];
    private final Rect rect = new Rect();

    /** Called right after the menu is shown; {@code enabled} is false when it wasn't a long press. */
    public void begin(boolean enabled) {
        reset();
        active = enabled;
        childrenCancelled = false;
    }

    /**
     * True once, for the first event taken from the chat: the caller then sends the chat a
     * cancel so the long-pressed message doesn't stay pressed.
     */
    public boolean takeCancelChildren() {
        if (childrenCancelled) {
            return false;
        }
        childrenCancelled = true;
        return true;
    }

    private void reset() {
        if (hovered != null) {
            hovered.setPressed(false);
            hovered.setSelected(false);
        }
        hovered = null;
        active = false;
        dragging = false;
        startX = Float.NaN;
    }

    /**
     * @param root  the view receiving the gesture (its coordinates are converted to the screen)
     * @param items the menu's items, null when no menu is open
     * @return true when the event was used for the menu and must not reach the chat
     */
    public boolean onTouch(MotionEvent ev, View root, ActionBarMenuSubItem[] items) {
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            reset(); // a new gesture: from here the menu works with ordinary taps
            return false;
        }
        if (!active) {
            return false;
        }
        if (items == null) {
            reset();
            return false;
        }
        root.getLocationOnScreen(location);
        final float screenX = location[0] + ev.getX();
        final float screenY = location[1] + ev.getY();
        if (Float.isNaN(startX)) {
            startX = screenX;
            startY = screenY;
        }
        if (!dragging && (Math.abs(screenX - startX) > AndroidUtilities.dp(12) || Math.abs(screenY - startY) > AndroidUtilities.dp(12))) {
            dragging = true;
        }
        final ActionBarMenuSubItem hit = dragging ? itemAt(items, screenX, screenY) : null;
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (hit != hovered) {
                    if (hovered != null) {
                        hovered.setPressed(false);
                        hovered.setSelected(false);
                    }
                    hovered = hit;
                    if (hit != null) {
                        hit.setPressed(true);
                        hit.setSelected(true);
                        hit.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP: {
                final ActionBarMenuSubItem chosen = hit;
                reset();
                if (chosen != null) {
                    chosen.performClick();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;
            default:
                return true;
        }
    }

    private ActionBarMenuSubItem itemAt(ActionBarMenuSubItem[] items, float x, float y) {
        for (ActionBarMenuSubItem item : items) {
            if (item == null || !item.isShown() || !item.isEnabled() || item.getWidth() == 0) {
                continue;
            }
            item.getLocationOnScreen(location);
            rect.set(location[0], location[1], location[0] + item.getWidth(), location[1] + item.getHeight());
            if (rect.contains((int) x, (int) y)) {
                return item;
            }
        }
        return null;
    }
}
