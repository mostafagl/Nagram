package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;

import java.util.ArrayList;

/**
 * A Parvagram settings page described as a list of rows: headers, switches, links and notes.
 * Subclasses fill {@link #buildRows}; the same rows feed the settings search.
 */
public abstract class ParvaPageActivity extends BaseNekoSettingsActivity {

    public interface BoolGetter {
        boolean get();
    }

    public interface BoolSetter {
        void set(boolean value);
    }

    public interface TextGetter {
        CharSequence get();
    }

    public static final class Row {
        public static final int HEADER = 0, CHECK = 1, LINK = 2, INFO = 3, SHADOW = 4;
        public int kind;
        public String key;
        public int title;
        public int description;
        public CharSequence titleText;
        BoolGetter getter;
        BoolSetter setter;
        Runnable click;
        TextGetter value;

        public CharSequence title() {
            return titleText != null ? titleText : title != 0 ? LocaleController.getString(title) : "";
        }

        public CharSequence description() {
            return description != 0 ? LocaleController.getString(description) : null;
        }
    }

    protected final ArrayList<Row> rows = new ArrayList<>();
    private final ArrayList<Row> positions = new ArrayList<>();

    protected Row header(int title) {
        final Row r = new Row();
        r.kind = Row.HEADER;
        r.title = title;
        rows.add(r);
        return r;
    }

    protected Row check(String key, int title, int description, BoolGetter getter, BoolSetter setter) {
        final Row r = new Row();
        r.kind = Row.CHECK;
        r.key = key;
        r.title = title;
        r.description = description;
        r.getter = getter;
        r.setter = setter;
        rows.add(r);
        return r;
    }

    protected Row link(String key, int title, int description, Runnable click) {
        final Row r = new Row();
        r.kind = Row.LINK;
        r.key = key;
        r.title = title;
        r.description = description;
        r.click = click;
        rows.add(r);
        return r;
    }

    /** A link showing its current value on the right (for choices). */
    protected Row choice(String key, int title, TextGetter value, Runnable click) {
        final Row r = link(key, title, 0, click);
        r.value = value;
        return r;
    }

    protected Row info(int text) {
        final Row r = new Row();
        r.kind = Row.INFO;
        r.title = text;
        rows.add(r);
        return r;
    }

    protected void shadow() {
        final Row r = new Row();
        r.kind = Row.SHADOW;
        rows.add(r);
    }

    protected abstract void buildRows();

    /** The rows, built without showing the page (for the settings search). */
    public ArrayList<Row> getRowsForSearch() {
        rows.clear();
        buildRows();
        return new ArrayList<>(rows);
    }

    @Override
    protected void updateRows() {
        super.updateRows();
        rows.clear();
        positions.clear();
        buildRows();
        for (Row r : rows) {
            if (r.key != null) {
                addRow(r.key);
            } else {
                addRow();
            }
            positions.add(r);
        }
    }

    /** Re-reads every row (after a change that affects others). */
    protected void refresh() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position < 0 || position >= positions.size()) {
            return;
        }
        final Row r = positions.get(position);
        if (r.kind == Row.CHECK) {
            final boolean value = !r.getter.get();
            r.setter.set(value);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(r.getter.get());
            }
        } else if (r.kind == Row.LINK && r.click != null) {
            r.click.run();
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private class ListAdapter extends BaseListAdapter {

        ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial, boolean divider) {
            if (position < 0 || position >= positions.size()) {
                return;
            }
            final Row r = positions.get(position);
            switch (holder.getItemViewType()) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(r.title());
                    break;
                case TYPE_INFO_PRIVACY:
                    ((TextInfoPrivacyCell) holder.itemView).setText(r.title());
                    break;
                case TYPE_CHECK: {
                    final TextCheckCell cell = (TextCheckCell) holder.itemView;
                    final CharSequence description = r.description();
                    if (description != null) {
                        cell.setTextAndValueAndCheck(r.title().toString(), description.toString(), r.getter.get(), true, divider);
                    } else {
                        cell.setTextAndCheck(r.title(), r.getter.get(), divider);
                    }
                    break;
                }
                case TYPE_DETAIL_SETTINGS: {
                    final TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                    cell.setMultilineDetail(true);
                    cell.setTextAndValue(r.title(), r.description(), divider);
                    break;
                }
                case TYPE_SETTINGS: {
                    final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (r.value != null) {
                        cell.setTextAndValue(r.title(), r.value.get(), divider);
                    } else {
                        cell.setText(r.title(), divider);
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= positions.size()) {
                return TYPE_SHADOW;
            }
            final Row r = positions.get(position);
            switch (r.kind) {
                case Row.HEADER:
                    return TYPE_HEADER;
                case Row.CHECK:
                    return TYPE_CHECK;
                case Row.INFO:
                    return TYPE_INFO_PRIVACY;
                case Row.LINK:
                    return r.description != 0 ? TYPE_DETAIL_SETTINGS : TYPE_SETTINGS;
                default:
                    return TYPE_SHADOW;
            }
        }
    }
}
