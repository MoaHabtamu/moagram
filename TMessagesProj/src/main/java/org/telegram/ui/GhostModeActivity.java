/*
 * This is the source code of Telegram for Android v. 10.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.GhostModeController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class GhostModeActivity extends BaseFragment {

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int rowCount;
    private int masterRow;
    private int masterDetailRow;
    private int featuresHeaderRow;
    private int hideOnlineRow;
    private int stealthSendRow;
    private int hideTypingRow;
    private int ghostReadRow;
    private int markOnReplyRow;
    private int hideStoriesRow;
    private int noticeSectionRow;
    private int localHistoryRow;
    private int localHistoryDetailRow;
    private int noticeDetailRow;

    private final static int TYPE_CHECK = 0;
    private final static int TYPE_INFO = 1;
    private final static int TYPE_HEADER = 2;
    private final static int TYPE_SHADOW = 3;
    private final static int TYPE_SETTINGS = 4;

    public GhostModeActivity() {
        this(UserConfig.selectedAccount);
    }

    public GhostModeActivity(int account) {
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
        masterRow = rowCount++;
        masterDetailRow = rowCount++;
        featuresHeaderRow = rowCount++;
        hideOnlineRow = rowCount++;
        stealthSendRow = rowCount++;
        hideTypingRow = rowCount++;
        ghostReadRow = rowCount++;
        markOnReplyRow = rowCount++;
        hideStoriesRow = rowCount++;
        noticeSectionRow = rowCount++;
        localHistoryRow = rowCount++;
        localHistoryDetailRow = rowCount++;
        noticeDetailRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.GhostMode));
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
            GhostModeController ghostMode = GhostModeController.getInstance(currentAccount);
            if (position == masterRow) {
                ghostMode.setEnabled(!ghostMode.enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ghostMode.enabled);
                }
                if (ghostMode.enabled && ghostMode.hideOnline) {
                    MessagesController.getInstance(currentAccount).sendOfflineStatus();
                }
                listAdapter.notifyDataSetChanged();
            } else if (position == localHistoryRow) {
                presentFragment(new LocalHistoryActivity(currentAccount));
            } else if (!ghostMode.enabled) {
                return;
            } else if (position == hideOnlineRow) {
                ghostMode.hideOnline = !ghostMode.hideOnline;
                ghostMode.save();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ghostMode.hideOnline);
                }
                if (ghostMode.hideOnline) {
                    MessagesController.getInstance(currentAccount).sendOfflineStatus();
                }
            } else if (position == stealthSendRow) {
                ghostMode.stealthSend = !ghostMode.stealthSend;
                ghostMode.save();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ghostMode.stealthSend);
                }
            } else if (position == hideTypingRow) {
                ghostMode.hideTyping = !ghostMode.hideTyping;
                ghostMode.save();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ghostMode.hideTyping);
                }
            } else if (position == ghostReadRow) {
                ghostMode.ghostRead = !ghostMode.ghostRead;
                ghostMode.save();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ghostMode.ghostRead);
                }
                listAdapter.notifyItemChanged(markOnReplyRow);
            } else if (position == markOnReplyRow) {
                if (!ghostMode.ghostRead) {
                    return;
                }
                ghostMode.markOnReply = !ghostMode.markOnReply;
                ghostMode.save();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ghostMode.markOnReply);
                }
            } else if (position == hideStoriesRow) {
                ghostMode.hideStories = !ghostMode.hideStories;
                ghostMode.save();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ghostMode.hideStories);
                }
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

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position == masterRow || position == localHistoryRow) {
                return true;
            }
            GhostModeController ghostMode = GhostModeController.getInstance(currentAccount);
            if (!ghostMode.enabled) {
                return false;
            }
            if (position == markOnReplyRow) {
                return ghostMode.ghostRead;
            }
            return position == hideOnlineRow || position == stealthSendRow || position == hideTypingRow || position == ghostReadRow || position == hideStoriesRow;
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
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
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
                case TYPE_CHECK:
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    GhostModeController ghostMode = GhostModeController.getInstance(currentAccount);
                    boolean enabled = ghostMode.enabled;
                    if (position == masterRow) {
                        checkCell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.GhostModeEnable),
                                LocaleController.getString(R.string.GhostModeSubtitle),
                                ghostMode.enabled,
                                false,
                                false
                        );
                        checkCell.setEnabled(true, null);
                    } else if (position == hideOnlineRow) {
                        checkCell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.GhostModeOnline),
                                LocaleController.getString(R.string.GhostModeOnlineInfo),
                                ghostMode.hideOnline,
                                true,
                                true
                        );
                        checkCell.setEnabled(enabled, null);
                    } else if (position == stealthSendRow) {
                        checkCell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.GhostModeStealthSend),
                                LocaleController.getString(R.string.GhostModeStealthSendInfo),
                                ghostMode.stealthSend,
                                true,
                                true
                        );
                        checkCell.setEnabled(enabled, null);
                    } else if (position == hideTypingRow) {
                        checkCell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.GhostModeTyping),
                                LocaleController.getString(R.string.GhostModeTypingInfo),
                                ghostMode.hideTyping,
                                true,
                                true
                        );
                        checkCell.setEnabled(enabled, null);
                    } else if (position == ghostReadRow) {
                        checkCell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.GhostModeRead),
                                LocaleController.getString(R.string.GhostModeReadInfo),
                                ghostMode.ghostRead,
                                true,
                                true
                        );
                        checkCell.setEnabled(enabled, null);
                    } else if (position == markOnReplyRow) {
                        checkCell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.GhostModeMarkOnReply),
                                LocaleController.getString(R.string.GhostModeMarkOnReplyInfo),
                                ghostMode.markOnReply,
                                true,
                                true
                        );
                        checkCell.setEnabled(enabled && ghostMode.ghostRead, null);
                    } else if (position == hideStoriesRow) {
                        checkCell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.GhostModeStories),
                                LocaleController.getString(R.string.GhostModeStoriesInfo),
                                ghostMode.hideStories,
                                true,
                                false
                        );
                        checkCell.setEnabled(enabled, null);
                    }
                    break;
                case TYPE_SETTINGS:
                    TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                    if (position == localHistoryRow) {
                        settingsCell.setText(LocaleController.getString(R.string.GhostModeLocalHistoryLink), false);
                        settingsCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
                    }
                    break;
                case TYPE_INFO:
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == masterDetailRow) {
                        infoCell.setText(LocaleController.getString(R.string.GhostModeEnableInfo));
                    } else if (position == localHistoryDetailRow) {
                        infoCell.setText(LocaleController.getString(R.string.GhostModeLocalHistoryLinkInfo));
                    } else if (position == noticeDetailRow) {
                        infoCell.setText(LocaleController.getString(R.string.GhostModeNotice));
                    }
                    break;
                case TYPE_HEADER:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == featuresHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.PrivacyTitle));
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == masterRow || position == hideOnlineRow || position == stealthSendRow || position == hideTypingRow || position == ghostReadRow || position == markOnReplyRow || position == hideStoriesRow) {
                return TYPE_CHECK;
            } else if (position == localHistoryRow) {
                return TYPE_SETTINGS;
            } else if (position == masterDetailRow || position == localHistoryDetailRow || position == noticeDetailRow) {
                return TYPE_INFO;
            } else if (position == featuresHeaderRow) {
                return TYPE_HEADER;
            }
            return TYPE_SHADOW;
        }
    }
}
