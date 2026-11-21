package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/** App-wide local retention, story cache, and content-saving preferences. */
public class LocalHistoryActivity extends BaseFragment {

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int rowCount;
    private int messagesHeaderRow;
    private int saveDeletedMessagesRow;
    private int saveDeletedMediaRow;
    private int saveEditHistoryRow;
    private int saveSelfDestructingMediaRow;
    private int messagesShadowRow;
    private int storiesHeaderRow;
    private int cacheStoryViewersRow;
    private int clearStoryViewsCacheRow;
    private int storiesShadowRow;
    private int restrictionsHeaderRow;
    private int saveRestrictedContentRow;
    private int bypassChannelRestrictionsRow;
    private int scopeInfoRow;

    private static final int TYPE_CHECK = 0;
    private static final int TYPE_SETTINGS = 1;
    private static final int TYPE_HEADER = 2;
    private static final int TYPE_INFO = 3;
    private static final int TYPE_SHADOW = 4;

    public LocalHistoryActivity() {
        this(UserConfig.selectedAccount);
    }

    public LocalHistoryActivity(int account) {
        setCurrentAccount(account);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        messagesHeaderRow = rowCount++;
        saveDeletedMessagesRow = rowCount++;
        saveDeletedMediaRow = rowCount++;
        saveEditHistoryRow = rowCount++;
        saveSelfDestructingMediaRow = rowCount++;
        messagesShadowRow = rowCount++;
        storiesHeaderRow = rowCount++;
        cacheStoryViewersRow = rowCount++;
        clearStoryViewsCacheRow = rowCount++;
        storiesShadowRow = rowCount++;
        restrictionsHeaderRow = rowCount++;
        saveRestrictedContentRow = rowCount++;
        bypassChannelRestrictionsRow = rowCount++;
        scopeInfoRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.LocalHistoryTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == saveDeletedMessagesRow) {
                SharedConfig.setSaveDeletedMessages(!SharedConfig.saveDeletedMessages);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.saveDeletedMessages);
                }
                listAdapter.notifyItemChanged(saveDeletedMediaRow);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.moagramConfigChanged);
            } else if (position == saveDeletedMediaRow) {
                if (!SharedConfig.saveDeletedMessages) {
                    return;
                }
                SharedConfig.setSaveDeletedMedia(!SharedConfig.saveDeletedMedia);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.saveDeletedMedia);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.moagramConfigChanged);
            } else if (position == saveEditHistoryRow) {
                SharedConfig.setSaveEditHistory(!SharedConfig.saveEditHistory);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.saveEditHistory);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.moagramConfigChanged);
            } else if (position == saveSelfDestructingMediaRow) {
                SharedConfig.setKeepSelfDestructingMedia(!SharedConfig.keepSelfDestructingMedia);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.keepSelfDestructingMedia);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.moagramConfigChanged);
            } else if (position == cacheStoryViewersRow) {
                SharedConfig.setCacheStoryViewers(!SharedConfig.cacheStoryViewers);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.cacheStoryViewers);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.moagramConfigChanged);
            } else if (position == clearStoryViewsCacheRow) {
                if (getParentActivity() == null) {
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(LocaleController.getString(R.string.ClearStoryViewsCache));
                builder.setMessage(LocaleController.getString(R.string.ClearStoryViewsCacheConfirm));
                builder.setPositiveButton(LocaleController.getString(R.string.ClearButton), (dialog, which) ->
                        MessagesStorage.getInstance(currentAccount).clearAllStoryViewsCache(() ->
                                BulletinFactory.of(LocalHistoryActivity.this)
                                        .createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.StoryViewsCacheCleared))
                                        .show()
                        )
                );
                builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                AlertDialog dialog = builder.create();
                showDialog(dialog);
                TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (button != null) {
                    button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                }
            } else if (position == saveRestrictedContentRow) {
                SharedConfig.allowSaveRestrictedMedia = !SharedConfig.allowSaveRestrictedMedia;
                SharedConfig.saveConfig();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.allowSaveRestrictedMedia);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.moagramConfigChanged);
            } else if (position == bypassChannelRestrictionsRow) {
                SharedConfig.setIgnoreAllRestrictions(!SharedConfig.ignoreAllRestrictions);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.ignoreAllRestrictions);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.moagramConfigChanged);
            }
        });

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position == saveDeletedMediaRow) {
                return SharedConfig.saveDeletedMessages;
            }
            return position == saveDeletedMessagesRow
                    || position == saveEditHistoryRow
                    || position == saveSelfDestructingMediaRow
                    || position == cacheStoryViewersRow
                    || position == clearStoryViewsCacheRow
                    || position == saveRestrictedContentRow
                    || position == bypassChannelRestrictionsRow;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_SETTINGS:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case TYPE_SHADOW:
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == saveDeletedMessagesRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.SaveDeletedMessages),
                                LocaleController.getString(R.string.SaveDeletedMessagesInfo),
                                SharedConfig.saveDeletedMessages, true, true);
                        cell.setEnabled(true, null);
                    } else if (position == saveDeletedMediaRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.SaveDeletedMedia),
                                LocaleController.getString(R.string.SaveDeletedMediaInfo),
                                SharedConfig.saveDeletedMedia, true, true);
                        cell.setEnabled(SharedConfig.saveDeletedMessages, null);
                    } else if (position == saveEditHistoryRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.SaveEditHistory),
                                LocaleController.getString(R.string.SaveEditHistoryInfo),
                                SharedConfig.saveEditHistory, true, true);
                        cell.setEnabled(true, null);
                    } else if (position == saveSelfDestructingMediaRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.SaveSelfDestructingMedia),
                                LocaleController.getString(R.string.SaveSelfDestructingMediaInfo),
                                SharedConfig.keepSelfDestructingMedia, true, false);
                        cell.setEnabled(true, null);
                    } else if (position == cacheStoryViewersRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.CacheStoryViewers),
                                LocaleController.getString(R.string.CacheStoryViewersInfo),
                                SharedConfig.cacheStoryViewers, true, true);
                        cell.setEnabled(true, null);
                    } else if (position == saveRestrictedContentRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.SaveRestrictedContent),
                                LocaleController.getString(R.string.SaveRestrictedContentInfo),
                                SharedConfig.allowSaveRestrictedMedia, true, true);
                        cell.setEnabled(true, null);
                    } else if (position == bypassChannelRestrictionsRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.BypassChannelRestrictions),
                                LocaleController.getString(R.string.BypassChannelRestrictionsInfo),
                                SharedConfig.ignoreAllRestrictions, true, false);
                        cell.setEnabled(true, null);
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == clearStoryViewsCacheRow) {
                        cell.setText(LocaleController.getString(R.string.ClearStoryViewsCache), false);
                        cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell header = (HeaderCell) holder.itemView;
                    if (position == messagesHeaderRow) {
                        header.setText(LocaleController.getString(R.string.LocalHistoryMessagesHeader));
                    } else if (position == storiesHeaderRow) {
                        header.setText(LocaleController.getString(R.string.LocalHistoryStoriesHeader));
                    } else if (position == restrictionsHeaderRow) {
                        header.setText(LocaleController.getString(R.string.LocalHistoryRestrictionsHeader));
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell info = (TextInfoPrivacyCell) holder.itemView;
                    if (position == scopeInfoRow) {
                        info.setText(LocaleController.getString(R.string.LocalHistoryScopeInfo));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == messagesHeaderRow || position == storiesHeaderRow || position == restrictionsHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == clearStoryViewsCacheRow) {
                return TYPE_SETTINGS;
            }
            if (position == scopeInfoRow) {
                return TYPE_INFO;
            }
            if (position == messagesShadowRow || position == storiesShadowRow) {
                return TYPE_SHADOW;
            }
            return TYPE_CHECK;
        }
    }
}
