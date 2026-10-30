/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.*;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.NekoXConfig;
import tw.nekomimi.nekogram.utils.AyuGhostUtils;
import xyz.nextalone.nagram.NaConfig;

/**
 * AyuGram's preferences, in its sections: ghost mode, spy (keeping what others delete, edit or
 * send with a timer), filters and customization. Every option has a short explanation.
 */
public class NekoGhostModeActivity extends BaseNekoSettingsActivity {
    // ghost mode
    private int GhostHeaderRow;
    private int GhostModeTitleRow;
    private int sendReadMessagePacketsRow;
    private int sendOnlinePacketsRow;
    private int sendUploadProgressRow;
    private int sendReadStoryPacketsRow;
    private int sendOfflineAfterOnlineRow;
    private int markReadAfterSendRow;
    private int useScheduledMessagesRow;
    private int ghostInfoRow;

    // spy
    private int spyHeaderRow;
    private int keepTimedMediaRow;
    private int saveDeletedRow;
    private int saveEditsRow;
    private int saveRestrictedRow;
    private int saveDeletedMediaRow;
    private int saveMediaPrivateRow;
    private int saveMediaGroupsRow;
    private int saveMediaChannelsRow;
    private int saveMediaBotsRow;
    private int spyInfoRow;

    // filters
    private int filtersHeaderRow;
    private int hideSponsoredRow;
    private int regexFiltersRow;
    private int localPremiumRow;
    private int filtersDividerRow;

    // customization
    private int customizationHeaderRow;
    private int deletedMarkRow;
    private int editedMarkRow;
    private int showGhostToggleInActionBarRow;
    private int showGhostToggleInDrawerRow;
    private int showKillButtonInDrawerRow;
    private int customizationDividerRow;

    private boolean ghostModeMenuExpanded;

    /** Row keys and their titles and explanations, for settings search. */
    public static final int[][] SEARCH_ROWS = {
            // {title, description (0: none)}; keys in SEARCH_KEYS, same order
            {R.string.GhostMode, R.string.GhostModeInfo},
            {R.string.MarkReadAfterAction, R.string.MarkReadAfterActionInfo},
            {R.string.UseScheduledMessages, R.string.UseScheduledMessagesInfo},
            {R.string.KeepTimedMedia, R.string.KeepTimedMediaInfo},
            {R.string.SaveDeletedMessagesTitle, R.string.SaveDeletedMessagesInfo},
            {R.string.SaveEditsTitle, R.string.SaveEditsInfo},
            {R.string.SaveRestrictedTitle, R.string.SaveRestrictedInfo},
            {R.string.SaveDeletedMediaTitle, R.string.SaveDeletedMediaInfo},
            {R.string.HideSponsoredTitle, R.string.HideSponsoredInfo},
            {R.string.RegexFiltersTitle, 0},
            {R.string.LocalPremiumTitle, R.string.LocalPremiumInfo},
            {R.string.DeletedMarkTitle, R.string.MarkTextHint},
            {R.string.EditedMarkTitle, R.string.MarkTextHint},
            {R.string.GhostToggleInActionBar, R.string.GhostToggleInActionBarInfo},
            {R.string.GhostToggleInDrawer, R.string.GhostToggleInDrawerInfo},
            {R.string.KillAppInDrawer, R.string.KillAppInDrawerInfo},
    };
    public static final String[] SEARCH_KEYS = {
            "ghostModeTitle", "markReadAfterSend", "useScheduledMessages", "keepTimedMedia", "saveDeleted", "saveEdits",
            "saveRestricted", "saveDeletedMedia", "hideSponsored", "regexFilters", "localPremium", "deletedMark", "editedMark",
            "showGhostToggleInActionBar", "showGhostToggleInDrawer", "showKillButtonInDrawer",
    };

    @Override
    protected void updateRows() {
        super.updateRows();

        GhostHeaderRow = addRow();
        GhostModeTitleRow = addRow("ghostModeTitle");
        if (ghostModeMenuExpanded) {
            sendReadMessagePacketsRow = addRow();
            sendOnlinePacketsRow = addRow();
            sendUploadProgressRow = addRow();
            sendReadStoryPacketsRow = addRow();
            sendOfflineAfterOnlineRow = addRow();
        } else {
            sendReadMessagePacketsRow = -1;
            sendOnlinePacketsRow = -1;
            sendUploadProgressRow = -1;
            sendReadStoryPacketsRow = -1;
            sendOfflineAfterOnlineRow = -1;
        }
        markReadAfterSendRow = addRow("markReadAfterSend");
        useScheduledMessagesRow = addRow("useScheduledMessages");
        ghostInfoRow = addRow();

        spyHeaderRow = addRow();
        keepTimedMediaRow = addRow("keepTimedMedia");
        saveDeletedRow = addRow("saveDeleted");
        saveEditsRow = addRow("saveEdits");
        saveRestrictedRow = addRow("saveRestricted");
        saveDeletedMediaRow = addRow("saveDeletedMedia");
        if (NekoConfig.saveDeletedMedia) {
            saveMediaPrivateRow = addRow();
            saveMediaGroupsRow = addRow();
            saveMediaChannelsRow = addRow();
            saveMediaBotsRow = addRow();
        } else {
            saveMediaPrivateRow = -1;
            saveMediaGroupsRow = -1;
            saveMediaChannelsRow = -1;
            saveMediaBotsRow = -1;
        }
        spyInfoRow = addRow();

        filtersHeaderRow = addRow();
        hideSponsoredRow = addRow("hideSponsored");
        regexFiltersRow = addRow("regexFilters");
        localPremiumRow = addRow("localPremium");
        filtersDividerRow = addRow();

        customizationHeaderRow = addRow();
        deletedMarkRow = addRow("deletedMark");
        editedMarkRow = addRow("editedMark");
        showGhostToggleInActionBarRow = addRow("showGhostToggleInActionBar");
        showGhostToggleInDrawerRow = addRow("showGhostToggleInDrawer");
        showKillButtonInDrawerRow = addRow("showKillButtonInDrawer");
        customizationDividerRow = addRow();
    }

    private void updateGhostViews() {
        var isActive = NekoConfig.isGhostModeActive();

        listAdapter.notifyItemChanged(GhostModeTitleRow, PARTIAL);
        if (ghostModeMenuExpanded) {
            listAdapter.notifyItemChanged(sendReadMessagePacketsRow, !isActive);
            listAdapter.notifyItemChanged(sendOnlinePacketsRow, !isActive);
            listAdapter.notifyItemChanged(sendUploadProgressRow, !isActive);
            listAdapter.notifyItemChanged(sendReadStoryPacketsRow, !isActive);
            listAdapter.notifyItemChanged(sendOfflineAfterOnlineRow, isActive);
        }

        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        NekoConfig.notifyGhostModeChanged();
    }

    private void refresh() {
        updateRows();
        listAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == GhostModeTitleRow) {
            ghostModeMenuExpanded ^= true;
            updateRows();
            listAdapter.notifyItemChanged(GhostModeTitleRow, PARTIAL);
            if (ghostModeMenuExpanded) {
                listAdapter.notifyItemRangeInserted(GhostModeTitleRow + 1, 5);
            } else {
                listAdapter.notifyItemRangeRemoved(GhostModeTitleRow + 1, 5);
            }
        } else if (position == sendReadMessagePacketsRow) {
            NekoConfig.putBoolean("sendReadMessagePackets", NekoConfig.sendReadMessagePackets ^= true);
            ((CheckBoxCell) view).setChecked(!NekoConfig.sendReadMessagePackets, true);
            AyuGhostUtils.setAllowReadPacket(false, -1);
            updateGhostViews();
        } else if (position == sendOnlinePacketsRow) {
            NekoConfig.putBoolean("sendOnlinePackets", NekoConfig.sendOnlinePackets ^= true);
            ((CheckBoxCell) view).setChecked(!NekoConfig.sendOnlinePackets, true);
            updateGhostViews();
        } else if (position == sendUploadProgressRow) {
            NekoConfig.putBoolean("sendUploadProgress", NekoConfig.sendUploadProgress ^= true);
            ((CheckBoxCell) view).setChecked(!NekoConfig.sendUploadProgress, true);
            updateGhostViews();
        } else if (position == sendReadStoryPacketsRow) {
            NekoConfig.putBoolean("sendReadStoryPackets", NekoConfig.sendReadStoryPackets ^= true);
            ((CheckBoxCell) view).setChecked(!NekoConfig.sendReadStoryPackets, true);
            updateGhostViews();
        } else if (position == sendOfflineAfterOnlineRow) {
            NekoConfig.putBoolean("sendOfflineAfterOnline", NekoConfig.sendOfflineAfterOnline ^= true);
            ((CheckBoxCell) view).setChecked(NekoConfig.sendOfflineAfterOnline, true);
            updateGhostViews();
        } else if (position == markReadAfterSendRow) {
            NekoConfig.putBoolean("markReadAfterSend", NekoConfig.markReadAfterSend ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.markReadAfterSend);
            AyuGhostUtils.setAllowReadPacket(false, -1);
        } else if (position == useScheduledMessagesRow) {
            NekoConfig.putBoolean("useScheduledMessages", NekoConfig.useScheduledMessages ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.useScheduledMessages);
        } else if (position == keepTimedMediaRow) {
            NekoConfig.putBoolean("keepTimedMedia", NekoConfig.keepTimedMedia ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.keepTimedMedia);
        } else if (position == saveDeletedRow) {
            ((TextCheckCell) view).setChecked(NaConfig.INSTANCE.getShowDeletedMessages().toggleConfigBool());
        } else if (position == saveEditsRow) {
            ((TextCheckCell) view).setChecked(NaConfig.INSTANCE.getShowMessageEditHistory().toggleConfigBool());
        } else if (position == saveRestrictedRow) {
            final boolean enabled = !(NaConfig.INSTANCE.getForceCopy().Bool() && NekoXConfig.disableFlagSecure);
            NaConfig.INSTANCE.getForceCopy().setConfigBool(enabled);
            NaConfig.INSTANCE.getDisableFlagSecure().setConfigBool(enabled);
            NekoXConfig.disableFlagSecure = enabled;
            ((TextCheckCell) view).setChecked(enabled);
        } else if (position == saveDeletedMediaRow) {
            NekoConfig.putBoolean("saveDeletedMedia", NekoConfig.saveDeletedMedia ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.saveDeletedMedia);
            refresh();
        } else if (position == saveMediaPrivateRow) {
            NekoConfig.putBoolean("saveMediaPrivate", NekoConfig.saveMediaPrivate ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.saveMediaPrivate);
        } else if (position == saveMediaGroupsRow) {
            NekoConfig.putBoolean("saveMediaGroups", NekoConfig.saveMediaGroups ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.saveMediaGroups);
        } else if (position == saveMediaChannelsRow) {
            NekoConfig.putBoolean("saveMediaChannels", NekoConfig.saveMediaChannels ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.saveMediaChannels);
        } else if (position == saveMediaBotsRow) {
            NekoConfig.putBoolean("saveMediaBots", NekoConfig.saveMediaBots ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.saveMediaBots);
        } else if (position == hideSponsoredRow) {
            ((TextCheckCell) view).setChecked(NekoConfig.hideSponsoredMessage.toggleConfigBool());
        } else if (position == regexFiltersRow) {
            presentFragment(new RegexFiltersSettingActivity());
        } else if (position == localPremiumRow) {
            ((TextCheckCell) view).setChecked(NekoConfig.localPremium.toggleConfigBool());
            showRestartBulletin();
        } else if (position == deletedMarkRow) {
            showTextInput(getString(R.string.DeletedMarkTitle), NekoConfig.deletedMark, "🧹", value -> {
                NekoConfig.deletedMark = value;
                NekoConfig.putString("deletedMark", value);
                listAdapter.notifyItemChanged(deletedMarkRow);
            });
        } else if (position == editedMarkRow) {
            showTextInput(getString(R.string.EditedMarkTitle), NaConfig.INSTANCE.getCustomEditedMessage().String(), getString(R.string.EditedMessage), value -> {
                NaConfig.INSTANCE.getCustomEditedMessage().setConfigString(value);
                listAdapter.notifyItemChanged(editedMarkRow);
            });
        } else if (position == showGhostToggleInActionBarRow) {
            NekoConfig.putBoolean("showGhostToggleInActionBar", NekoConfig.showGhostToggleInActionBar ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.showGhostToggleInActionBar);
            NekoConfig.notifyGhostModeChanged();
            NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        } else if (position == showGhostToggleInDrawerRow) {
            NekoConfig.putBoolean("showGhostToggleInDrawer", NekoConfig.showGhostToggleInDrawer ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.showGhostToggleInDrawer);
            NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        } else if (position == showKillButtonInDrawerRow) {
            NekoConfig.putBoolean("showKillButtonInDrawer", NekoConfig.showKillButtonInDrawer ^= true);
            ((TextCheckCell) view).setChecked(NekoConfig.showKillButtonInDrawer);
        }
    }

    private interface TextCallback {
        void onText(String value);
    }

    /** A one-line text input; an empty text restores the default. */
    private void showTextInput(String title, String value, String hint, TextCallback callback) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setSingleLine(true);
        editText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        editText.setHint(hint);
        editText.setText(value);
        editText.setSelection(editText.length());
        editText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        final FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 0, 24, 0));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setMessage(getString(R.string.MarkTextHint));
        builder.setView(container);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setNeutralButton(getString(R.string.Default), (d, w) -> callback.onText(""));
        builder.setPositiveButton(getString(R.string.Save), (d, w) -> callback.onText(editText.getText() == null ? "" : editText.getText().toString().trim()));
        showDialog(builder.create());
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.GhostAndSpyTitle);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private int getGhostModeSelectedCount() {
        int count = 0;
        if (!NekoConfig.sendReadMessagePackets) count++;
        if (!NekoConfig.sendOnlinePackets) count++;
        if (!NekoConfig.sendUploadProgress) count++;
        if (!NekoConfig.sendReadStoryPackets) count++;
        if (NekoConfig.sendOfflineAfterOnline) count++;
        return count;
    }

    private static String getString(int id) {
        return LocaleController.getString(id);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        private void check(TextCheckCell cell, int title, int description, boolean checked, boolean divider) {
            cell.setEnabled(true, null);
            cell.setTextAndValueAndCheck(getString(title), getString(description), checked, true, divider);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean payload, boolean divider) {
            switch (holder.getItemViewType()) {
                case TYPE_SHADOW:
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == ghostInfoRow) {
                        cell.setText(getString(R.string.GhostModeInfo));
                    } else if (position == spyInfoRow) {
                        cell.setText(getString(R.string.SpyInfo));
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == GhostHeaderRow) {
                        headerCell.setText(getString(R.string.GhostElements));
                    } else if (position == spyHeaderRow) {
                        headerCell.setText(getString(R.string.SpyHeader));
                    } else if (position == filtersHeaderRow) {
                        headerCell.setText(getString(R.string.FiltersHeader));
                    } else if (position == customizationHeaderRow) {
                        headerCell.setText(getString(R.string.CustomizationHeader));
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == deletedMarkRow) {
                        cell.setTextAndValue(getString(R.string.DeletedMarkTitle), TextUtils.isEmpty(NekoConfig.deletedMark) ? getString(R.string.DeletedMarkIcon) : NekoConfig.deletedMark, divider);
                    } else if (position == editedMarkRow) {
                        final String edited = NaConfig.INSTANCE.getCustomEditedMessage().String();
                        cell.setTextAndValue(getString(R.string.EditedMarkTitle), TextUtils.isEmpty(edited) ? getString(R.string.EditedMessage) : edited, divider);
                    } else if (position == regexFiltersRow) {
                        cell.setText(getString(R.string.RegexFiltersTitle), divider);
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == markReadAfterSendRow) {
                        check(cell, R.string.MarkReadAfterAction, R.string.MarkReadAfterActionInfo, NekoConfig.markReadAfterSend, divider);
                    } else if (position == useScheduledMessagesRow) {
                        check(cell, R.string.UseScheduledMessages, R.string.UseScheduledMessagesInfo, NekoConfig.useScheduledMessages, divider);
                    } else if (position == keepTimedMediaRow) {
                        check(cell, R.string.KeepTimedMedia, R.string.KeepTimedMediaInfo, NekoConfig.keepTimedMedia, divider);
                    } else if (position == saveDeletedRow) {
                        check(cell, R.string.SaveDeletedMessagesTitle, R.string.SaveDeletedMessagesInfo, NaConfig.INSTANCE.getShowDeletedMessages().Bool(), divider);
                    } else if (position == saveEditsRow) {
                        check(cell, R.string.SaveEditsTitle, R.string.SaveEditsInfo, NaConfig.INSTANCE.getShowMessageEditHistory().Bool(), divider);
                    } else if (position == saveRestrictedRow) {
                        check(cell, R.string.SaveRestrictedTitle, R.string.SaveRestrictedInfo, NaConfig.INSTANCE.getForceCopy().Bool() && NekoXConfig.disableFlagSecure, divider);
                    } else if (position == saveDeletedMediaRow) {
                        check(cell, R.string.SaveDeletedMediaTitle, R.string.SaveDeletedMediaInfo, NekoConfig.saveDeletedMedia, divider);
                    } else if (position == saveMediaPrivateRow) {
                        cell.setTextAndCheck(getString(R.string.SaveMediaPrivate), NekoConfig.saveMediaPrivate, divider);
                    } else if (position == saveMediaGroupsRow) {
                        cell.setTextAndCheck(getString(R.string.SaveMediaGroups), NekoConfig.saveMediaGroups, divider);
                    } else if (position == saveMediaChannelsRow) {
                        cell.setTextAndCheck(getString(R.string.SaveMediaChannels), NekoConfig.saveMediaChannels, divider);
                    } else if (position == saveMediaBotsRow) {
                        cell.setTextAndCheck(getString(R.string.SaveMediaBots), NekoConfig.saveMediaBots, divider);
                    } else if (position == hideSponsoredRow) {
                        check(cell, R.string.HideSponsoredTitle, R.string.HideSponsoredInfo, NekoConfig.hideSponsoredMessage.Bool(), divider);
                    } else if (position == localPremiumRow) {
                        check(cell, R.string.LocalPremiumTitle, R.string.LocalPremiumInfo, NekoConfig.localPremium.Bool(), divider);
                    } else if (position == showGhostToggleInActionBarRow) {
                        check(cell, R.string.GhostToggleInActionBar, R.string.GhostToggleInActionBarInfo, NekoConfig.showGhostToggleInActionBar, divider);
                    } else if (position == showGhostToggleInDrawerRow) {
                        check(cell, R.string.GhostToggleInDrawer, R.string.GhostToggleInDrawerInfo, NekoConfig.showGhostToggleInDrawer, divider);
                    } else if (position == showKillButtonInDrawerRow) {
                        check(cell, R.string.KillAppInDrawer, R.string.KillAppInDrawerInfo, NekoConfig.showKillButtonInDrawer, divider);
                    }
                    break;
                }
                case TYPE_CHECK2: {
                    TextCheckCell2 textCheckCell2 = (TextCheckCell2) holder.itemView;
                    if (position == GhostModeTitleRow) {
                        int selectedCount = getGhostModeSelectedCount();
                        textCheckCell2.setTextAndCheck(getString(R.string.GhostMode), NekoConfig.isGhostModeActive(), divider, true);
                        textCheckCell2.setCollapseArrow(String.format(Locale.US, "%d/5", selectedCount), !ghostModeMenuExpanded, () -> {
                            NekoConfig.toggleGhostMode();
                            AyuGhostUtils.setAllowReadPacket(false, -1);
                            updateGhostViews();
                        });
                    }
                    textCheckCell2.getCheckBox().setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
                    textCheckCell2.getCheckBox().setDrawIconType(0);
                    break;
                }
                case TYPE_CHECKBOX2: {
                    CheckBoxCell checkBoxCell = (CheckBoxCell) holder.itemView;
                    if (position == sendReadMessagePacketsRow) {
                        checkBoxCell.setText(getString(R.string.DontReadMessages), "", !NekoConfig.sendReadMessagePackets, divider, true);
                    } else if (position == sendOnlinePacketsRow) {
                        checkBoxCell.setText(getString(R.string.DontSendOnlinePackets), "", !NekoConfig.sendOnlinePackets, divider, true);
                    } else if (position == sendUploadProgressRow) {
                        checkBoxCell.setText(getString(R.string.DontSendUploadProgress), "", !NekoConfig.sendUploadProgress, divider, true);
                    } else if (position == sendReadStoryPacketsRow) {
                        checkBoxCell.setText(getString(R.string.DontReadStories), "", !NekoConfig.sendReadStoryPackets, divider, true);
                    } else if (position == sendOfflineAfterOnlineRow) {
                        checkBoxCell.setText(getString(R.string.SendOfflinePacketAfterOnline), "", NekoConfig.sendOfflineAfterOnline, divider, true);
                    }
                    checkBoxCell.setPad(1);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == filtersDividerRow || position == customizationDividerRow) {
                return TYPE_SHADOW;
            }
            if (position == ghostInfoRow || position == spyInfoRow) {
                return TYPE_INFO_PRIVACY;
            }
            if (position == GhostHeaderRow || position == spyHeaderRow || position == filtersHeaderRow || position == customizationHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == GhostModeTitleRow) {
                return TYPE_CHECK2;
            }
            if (position >= sendReadMessagePacketsRow && position <= sendOfflineAfterOnlineRow && sendReadMessagePacketsRow != -1) {
                return TYPE_CHECKBOX2;
            }
            if (position == deletedMarkRow || position == editedMarkRow || position == regexFiltersRow) {
                return TYPE_SETTINGS;
            }
            return TYPE_CHECK;
        }
    }
}
