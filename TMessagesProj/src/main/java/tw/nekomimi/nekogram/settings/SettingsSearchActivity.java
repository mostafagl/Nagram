package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.SettingsHelper;
import tw.nekomimi.nekogram.helpers.SettingsSearchResult;
import tw.nekomimi.nekogram.utils.PersianSearch;

/**
 * Search over Parvagram's settings (N-Config pages, ghost mode & spy, proxy and font pages).
 * Matching ignores آ/ا, ی/ي, ک/ك, diacritics, half-spaces, case and digit scripts, and looks at
 * each setting's explanation and section too.
 */
public class SettingsSearchActivity extends BaseFragment {

    private final ArrayList<SettingsSearchResult> all = new ArrayList<>();
    private final ArrayList<SettingsSearchResult> results = new ArrayList<>();
    private RecyclerListView listView;
    private TextView emptyView;
    private String query = "";

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SettingsSearchTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        final ActionBarMenuItem searchItem = actionBar.createMenu().addItem(0, R.drawable.outline_header_search).setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchCollapse() {
                        finishFragment();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        query = editText.getText() == null ? "" : editText.getText().toString();
                        filter();
                    }
                });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.SettingsSearchHint));

        all.addAll(SettingsHelper.onCreateSearchArray(fragment -> AndroidUtilities.runOnUIThread(() -> presentFragment(fragment))));

        final FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < results.size()) {
                AndroidUtilities.hideKeyboard(fragmentView);
                results.get(position).openRunnable.run();
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        AndroidUtilities.runOnUIThread(() -> searchItem.openSearch(true), 150);
        filter();
        return fragmentView;
    }

    private void filter() {
        results.clear();
        if (!TextUtils.isEmpty(query.trim())) {
            for (SettingsSearchResult item : all) {
                if (PersianSearch.matchesAll(query, item.searchTitle, item.description, item.path2)) {
                    results.add(item);
                }
            }
            // Title matches first.
            final ArrayList<SettingsSearchResult> byTitle = new ArrayList<>();
            final ArrayList<SettingsSearchResult> other = new ArrayList<>();
            for (SettingsSearchResult item : results) {
                (PersianSearch.matchesAll(query, item.searchTitle) ? byTitle : other).add(item);
            }
            results.clear();
            results.addAll(byTitle);
            results.addAll(other);
        }
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            emptyView.setText(LocaleController.getString(TextUtils.isEmpty(query.trim()) ? R.string.SettingsSearchEmpty : R.string.SettingsNoResults));
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
            cell.setMultilineDetail(true);
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final SettingsSearchResult item = results.get(position);
            final StringBuilder detail = new StringBuilder();
            if (!TextUtils.isEmpty(item.path2)) {
                detail.append(item.path2);
            }
            if (!TextUtils.isEmpty(item.description)) {
                if (detail.length() > 0) {
                    detail.append(" · ");
                }
                detail.append(item.description);
            }
            ((TextDetailSettingsCell) holder.itemView).setTextAndValue(item.searchTitle, detail.length() > 0 ? detail.toString() : item.path1, position < results.size() - 1);
        }

        @Override
        public int getItemCount() {
            return results.size();
        }
    }
}
