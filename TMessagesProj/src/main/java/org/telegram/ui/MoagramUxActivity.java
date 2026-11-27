package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MoagramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/** Everyday Telegram UX preferences: confirmations, de-cluttering, and detail toggles. */
public class MoagramUxActivity extends BaseFragment {

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int rowCount;
    private int confirmationsHeaderRow;
    private int confirmCallsRow;
    private int confirmStickersRow;
    private int confirmGifsRow;
    private int confirmationsShadowRow;
    private int interfaceHeaderRow;
    private int hideStoriesRow;
    private int hideGiftButtonRow;
    private int disableNextChannelRow;
    private int interfaceShadowRow;
    private int detailsHeaderRow;
    private int showSecondsRow;
    private int showProfileIdsRow;
    private int detailsShadowRow;
    private int downloadsHeaderRow;
    private int downloadBoostRow;
    private int endShadowRow;

    private static final int TYPE_CHECK = 0;
    private static final int TYPE_HEADER = 1;
    private static final int TYPE_SHADOW = 2;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        MoagramConfig.ensureLoaded();
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        confirmationsHeaderRow = rowCount++;
        confirmCallsRow = rowCount++;
        confirmStickersRow = rowCount++;
        confirmGifsRow = rowCount++;
        confirmationsShadowRow = rowCount++;
        interfaceHeaderRow = rowCount++;
        hideStoriesRow = rowCount++;
        hideGiftButtonRow = rowCount++;
        disableNextChannelRow = rowCount++;
        interfaceShadowRow = rowCount++;
        detailsHeaderRow = rowCount++;
        showSecondsRow = rowCount++;
        showProfileIdsRow = rowCount++;
        detailsShadowRow = rowCount++;
        downloadsHeaderRow = rowCount++;
        downloadBoostRow = rowCount++;
        endShadowRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MoagramUxTitle));
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
            if (!(view instanceof TextCheckCell)) {
                return;
            }
            TextCheckCell cell = (TextCheckCell) view;
            if (position == confirmCallsRow) {
                boolean next = !MoagramConfig.isConfirmCalls();
                MoagramConfig.setConfirmCalls(next);
                cell.setChecked(next);
            } else if (position == confirmStickersRow) {
                boolean next = !MoagramConfig.isConfirmStickers();
                MoagramConfig.setConfirmStickers(next);
                cell.setChecked(next);
            } else if (position == confirmGifsRow) {
                boolean next = !MoagramConfig.isConfirmGifs();
                MoagramConfig.setConfirmGifs(next);
                cell.setChecked(next);
            } else if (position == hideStoriesRow) {
                boolean next = !MoagramConfig.isHideStoriesBar();
                MoagramConfig.setHideStoriesBar(next);
                cell.setChecked(next);
            } else if (position == hideGiftButtonRow) {
                boolean next = !MoagramConfig.isHideGiftButton();
                MoagramConfig.setHideGiftButton(next);
                cell.setChecked(next);
            } else if (position == disableNextChannelRow) {
                boolean next = !MoagramConfig.isDisableNextChannelJump();
                MoagramConfig.setDisableNextChannelJump(next);
                cell.setChecked(next);
            } else if (position == showSecondsRow) {
                boolean next = !MoagramConfig.isShowSecondsInTimestamps();
                MoagramConfig.setShowSecondsInTimestamps(next);
                cell.setChecked(next);
            } else if (position == showProfileIdsRow) {
                boolean next = !MoagramConfig.isShowProfileIds();
                MoagramConfig.setShowProfileIds(next);
                cell.setChecked(next);
            } else if (position == downloadBoostRow) {
                boolean next = !MoagramConfig.isDownloadSpeedBoost();
                MoagramConfig.setDownloadSpeedBoost(next);
                cell.setChecked(next);
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
            return holder.getItemViewType() == TYPE_CHECK;
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
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == confirmCallsRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.MoagramUxConfirmCalls),
                                LocaleController.getString(R.string.MoagramUxConfirmCallsInfo),
                                MoagramConfig.isConfirmCalls(), true, true);
                    } else if (position == confirmStickersRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.MoagramUxConfirmStickers),
                                LocaleController.getString(R.string.MoagramUxConfirmStickersInfo),
                                MoagramConfig.isConfirmStickers(), true, true);
                    } else if (position == confirmGifsRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.MoagramUxConfirmGifs),
                                LocaleController.getString(R.string.MoagramUxConfirmGifsInfo),
                                MoagramConfig.isConfirmGifs(), true, false);
                    } else if (position == hideStoriesRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.MoagramUxHideStories),
                                LocaleController.getString(R.string.MoagramUxHideStoriesInfo),
                                MoagramConfig.isHideStoriesBar(), true, true);
                    } else if (position == hideGiftButtonRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.MoagramUxHideGiftButton),
                                LocaleController.getString(R.string.MoagramUxHideGiftButtonInfo),
                                MoagramConfig.isHideGiftButton(), true, true);
                    } else if (position == disableNextChannelRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.MoagramUxDisableNextChannel),
                                LocaleController.getString(R.string.MoagramUxDisableNextChannelInfo),
                                MoagramConfig.isDisableNextChannelJump(), true, false);
                    } else if (position == showSecondsRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.MoagramUxShowSeconds),
                                LocaleController.getString(R.string.MoagramUxShowSecondsInfo),
                                MoagramConfig.isShowSecondsInTimestamps(), true, true);
                    } else if (position == showProfileIdsRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.MoagramUxShowProfileIds),
                                LocaleController.getString(R.string.MoagramUxShowProfileIdsInfo),
                                MoagramConfig.isShowProfileIds(), true, false);
                    } else if (position == downloadBoostRow) {
                        cell.setTextAndValueAndCheck(
                                LocaleController.getString(R.string.MoagramUxDownloadBoost),
                                LocaleController.getString(R.string.MoagramUxDownloadBoostInfo),
                                MoagramConfig.isDownloadSpeedBoost(), true, false);
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell header = (HeaderCell) holder.itemView;
                    if (position == confirmationsHeaderRow) {
                        header.setText(LocaleController.getString(R.string.MoagramUxConfirmationsHeader));
                    } else if (position == interfaceHeaderRow) {
                        header.setText(LocaleController.getString(R.string.MoagramUxInterfaceHeader));
                    } else if (position == detailsHeaderRow) {
                        header.setText(LocaleController.getString(R.string.MoagramUxDetailsHeader));
                    } else if (position == downloadsHeaderRow) {
                        header.setText(LocaleController.getString(R.string.MoagramUxDownloadsHeader));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == confirmationsHeaderRow || position == interfaceHeaderRow
                    || position == detailsHeaderRow || position == downloadsHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == confirmationsShadowRow || position == interfaceShadowRow
                    || position == detailsShadowRow || position == endShadowRow) {
                return TYPE_SHADOW;
            }
            return TYPE_CHECK;
        }
    }
}
