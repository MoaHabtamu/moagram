package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.util.ArrayList;

public class EditHistoryBottomSheet extends BottomSheetWithRecyclerListView {

    public static class EditItem {
        public static final int TYPE_CURRENT = 0;
        public static final int TYPE_EDITED = 1;
        public static final int TYPE_ORIGINAL = 2;

        public final int type;
        public final String title;
        public final int date;
        public final CharSequence text;
        public final MessageObject messageObject;

        public EditItem(int type, String title, int date, CharSequence text, MessageObject messageObject) {
            this.type = type;
            this.title = title;
            this.date = date;
            this.text = text;
            this.messageObject = messageObject;
        }
    }

    private final MessageObject currentMessageObject;
    private final ArrayList<EditItem> items = new ArrayList<>();
    private RecyclerListView.SelectionAdapter adapter;

    public static void show(BaseFragment fragment, MessageObject messageObject) {
        if (fragment == null || fragment.getParentActivity() == null || messageObject == null) {
            return;
        }
        EditHistoryBottomSheet sheet = new EditHistoryBottomSheet(fragment, messageObject);
        fragment.showDialog(sheet);
    }

    public EditHistoryBottomSheet(BaseFragment fragment, MessageObject messageObject) {
        super(fragment, false, false);
        this.currentMessageObject = messageObject;

        fixNavigationBar();
        handleOffset = true;
        setShowHandle(true);
        setSlidingActionBar();

        recyclerListView.setPadding(backgroundPaddingLeft, AndroidUtilities.dp(8), backgroundPaddingLeft, AndroidUtilities.dp(16));
        recyclerListView.setClipToPadding(false);

        int currentDate = messageObject.messageOwner != null && messageObject.messageOwner.edit_date != 0 ? messageObject.messageOwner.edit_date : (messageObject.messageOwner != null ? messageObject.messageOwner.date : 0);
        CharSequence currentText = messageObject.messageText != null ? messageObject.messageText : (messageObject.messageOwner != null ? messageObject.messageOwner.message : "");
        items.add(new EditItem(EditItem.TYPE_CURRENT, LocaleController.getString(R.string.CurrentVersion), currentDate, currentText, messageObject));

        recyclerListView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < items.size()) {
                EditItem item = items.get(position);
                if (!TextUtils.isEmpty(item.text)) {
                    AndroidUtilities.addToClipboard(item.text);
                    BulletinFactory.of(container, resourcesProvider).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                } else if (item.messageObject != null && item.messageObject.messageOwner != null && item.messageObject.messageOwner.media != null && !(item.messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty)) {
                    openMedia(item);
                }
            }
        });

        recyclerListView.setOnItemLongClickListener((view, position) -> {
            if (position >= 0 && position < items.size()) {
                EditItem item = items.get(position);
                if (!TextUtils.isEmpty(item.text)) {
                    AndroidUtilities.addToClipboard(item.text);
                    BulletinFactory.of(container, resourcesProvider).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                    return true;
                }
            }
            return false;
        });

        fragment.getMessagesStorage().loadMessageEdits(messageObject.getDialogId(), messageObject.getId(), edits -> {
            items.clear();
            items.add(new EditItem(EditItem.TYPE_CURRENT, LocaleController.getString(R.string.CurrentVersion), currentDate, currentText, messageObject));

            if (edits != null && !edits.isEmpty()) {
                for (int i = edits.size() - 1; i >= 0; i--) {
                    TLRPC.Message editMsg = edits.get(i);
                    MessageObject editObj = new MessageObject(currentAccount, editMsg, false, false);
                    boolean isOriginal = (i == 0);
                    String title = isOriginal ? LocaleController.getString(R.string.OriginalVersion) : LocaleController.getString(R.string.EditedVersion);
                    int date = editMsg.edit_date != 0 ? editMsg.edit_date : editMsg.date;
                    CharSequence text = editObj.messageText != null ? editObj.messageText : editMsg.message;
                    items.add(new EditItem(isOriginal ? EditItem.TYPE_ORIGINAL : EditItem.TYPE_EDITED, title, date, text, editObj));
                }
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void openMedia(EditItem item) {
        if (item == null || item.messageObject == null || getBaseFragment() == null || getBaseFragment().getParentActivity() == null) {
            return;
        }
        MessageObject msgObj = item.messageObject;
        if (msgObj.isPhoto() || msgObj.isVideo() || msgObj.isRoundVideo() || (msgObj.getDocument() != null && MessageObject.isGifDocument(msgObj.getDocument()))) {
            PhotoViewer.getInstance().setParentActivity(getBaseFragment(), resourcesProvider);
            ArrayList<MessageObject> array = new ArrayList<>();
            array.add(msgObj);
            PhotoViewer.getInstance().openPhoto(array, 0, msgObj.getDialogId(), 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
        } else if (msgObj.isVoice() || msgObj.isMusic()) {
            MediaController.getInstance().playMessage(msgObj);
        } else {
            File f = null;
            if (msgObj.messageOwner != null && msgObj.messageOwner.attachPath != null && msgObj.messageOwner.attachPath.length() != 0) {
                f = new File(msgObj.messageOwner.attachPath);
            }
            if (f == null || !f.exists()) {
                f = FileLoader.getInstance(msgObj.currentAccount).getPathToMessage(msgObj.messageOwner);
            }
            if (f != null && f.exists()) {
                AndroidUtilities.openForView(msgObj, getBaseFragment().getParentActivity(), resourcesProvider, false);
            } else if (msgObj.getDocument() != null) {
                FileLoader.getInstance(msgObj.currentAccount).loadFile(msgObj.getDocument(), msgObj, FileLoader.PRIORITY_NORMAL, 0);
                BulletinFactory.of(container, resourcesProvider).createSimpleBulletin(R.raw.ic_download, LocaleController.getString(R.string.Downloading)).show();
            } else {
                AndroidUtilities.openForView(msgObj, getBaseFragment().getParentActivity(), resourcesProvider, false);
            }
        }
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.EditHistory);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return holder.getItemViewType() == 0;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                if (viewType == 1) {
                    TextInfoPrivacyCell hintCell = new TextInfoPrivacyCell(parent.getContext());
                    hintCell.setText(LocaleController.getString(R.string.SaveEditHistoryInfo));
                    return new RecyclerListView.Holder(hintCell);
                }
                FrameLayout container = new FrameLayout(parent.getContext());
                RecyclerView.LayoutParams rlp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = AndroidUtilities.dp(4);
                rlp.bottomMargin = AndroidUtilities.dp(4);
                rlp.leftMargin = AndroidUtilities.dp(12);
                rlp.rightMargin = AndroidUtilities.dp(12);
                container.setLayoutParams(rlp);

                LinearLayout card = new LinearLayout(parent.getContext());
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                        AndroidUtilities.dp(12),
                        Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider),
                        Theme.getColor(Theme.key_listSelector, resourcesProvider)
                ));
                card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));

                LinearLayout topRow = new LinearLayout(parent.getContext());
                topRow.setOrientation(LinearLayout.HORIZONTAL);

                TextView titleView = new TextView(parent.getContext());
                titleView.setTag("title");
                titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                titleView.setTypeface(AndroidUtilities.bold());
                titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
                topRow.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f));

                TextView dateView = new TextView(parent.getContext());
                dateView.setTag("date");
                dateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                dateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
                dateView.setGravity(Gravity.RIGHT);
                topRow.addView(dateView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

                card.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

                FrameLayout mediaContainer = new FrameLayout(parent.getContext());
                mediaContainer.setTag("mediaContainer");

                FrameLayout photoVideoContainer = new FrameLayout(parent.getContext());
                photoVideoContainer.setTag("photoVideoContainer");

                BackupImageView mediaImageView = new BackupImageView(parent.getContext());
                mediaImageView.setTag("mediaImageView");
                mediaImageView.setRoundRadius(AndroidUtilities.dp(8));
                photoVideoContainer.addView(mediaImageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

                ImageView playIcon = new ImageView(parent.getContext());
                playIcon.setTag("playIcon");
                playIcon.setImageResource(R.drawable.play_mini_video);
                playIcon.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(44), 0x66000000));
                playIcon.setScaleType(ImageView.ScaleType.CENTER);
                photoVideoContainer.addView(playIcon, LayoutHelper.createFrame(44, 44, Gravity.CENTER));

                TextView videoDuration = new TextView(parent.getContext());
                videoDuration.setTag("videoDuration");
                videoDuration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                videoDuration.setTextColor(0xffffffff);
                videoDuration.setTypeface(AndroidUtilities.bold());
                videoDuration.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(4), 0x66000000));
                videoDuration.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(2), AndroidUtilities.dp(4), AndroidUtilities.dp(2));
                photoVideoContainer.addView(videoDuration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 6, 6));

                mediaContainer.addView(photoVideoContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 160));

                LinearLayout docContainer = new LinearLayout(parent.getContext());
                docContainer.setTag("docContainer");
                docContainer.setOrientation(LinearLayout.HORIZONTAL);
                docContainer.setGravity(Gravity.CENTER_VERTICAL);
                docContainer.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(8), Theme.getColor(Theme.key_chat_inBubble, resourcesProvider)));
                docContainer.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));

                ImageView docIcon = new ImageView(parent.getContext());
                docIcon.setTag("docIcon");
                docIcon.setImageResource(R.drawable.msg_round_file_s);
                docIcon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_inMediaIcon, resourcesProvider), PorterDuff.Mode.SRC_IN));
                docContainer.addView(docIcon, LayoutHelper.createLinear(36, 36));

                LinearLayout docInfo = new LinearLayout(parent.getContext());
                docInfo.setOrientation(LinearLayout.VERTICAL);

                TextView docTitleView = new TextView(parent.getContext());
                docTitleView.setTag("docTitle");
                docTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                docTitleView.setTypeface(AndroidUtilities.bold());
                docTitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                docTitleView.setSingleLine(true);
                docTitleView.setEllipsize(TextUtils.TruncateAt.END);
                docInfo.addView(docTitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                TextView docSubtitleView = new TextView(parent.getContext());
                docSubtitleView.setTag("docSubtitle");
                docSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                docSubtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
                docSubtitleView.setSingleLine(true);
                docSubtitleView.setEllipsize(TextUtils.TruncateAt.END);
                docInfo.addView(docSubtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

                docContainer.addView(docInfo, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f, 10, 0, 0, 0));

                mediaContainer.addView(docContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                card.addView(mediaContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

                TextView textView = new TextView(parent.getContext());
                textView.setTag("text");
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                textView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
                card.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                container.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                return new RecyclerListView.Holder(container);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                if (holder.getItemViewType() == 1) {
                    return;
                }
                EditItem item = items.get(position);
                FrameLayout container = (FrameLayout) holder.itemView;
                LinearLayout card = (LinearLayout) container.getChildAt(0);
                LinearLayout topRow = (LinearLayout) card.getChildAt(0);
                TextView titleView = (TextView) topRow.getChildAt(0);
                TextView dateView = (TextView) topRow.getChildAt(1);
                FrameLayout mediaContainer = (FrameLayout) card.getChildAt(1);
                FrameLayout photoVideoContainer = (FrameLayout) mediaContainer.getChildAt(0);
                BackupImageView mediaImageView = (BackupImageView) photoVideoContainer.getChildAt(0);
                ImageView playIcon = (ImageView) photoVideoContainer.getChildAt(1);
                TextView videoDuration = (TextView) photoVideoContainer.getChildAt(2);
                LinearLayout docContainer = (LinearLayout) mediaContainer.getChildAt(1);
                ImageView docIcon = (ImageView) docContainer.getChildAt(0);
                LinearLayout docInfo = (LinearLayout) docContainer.getChildAt(1);
                TextView docTitleView = (TextView) docInfo.getChildAt(0);
                TextView docSubtitleView = (TextView) docInfo.getChildAt(1);
                TextView textView = (TextView) card.getChildAt(2);

                titleView.setText(item.title);
                if (item.date != 0) {
                    dateView.setText(LocaleController.formatSmallDateChat(item.date) + ", " + LocaleController.getInstance().getFormatterDay().format((long) item.date * 1000));
                } else {
                    dateView.setText("");
                }

                if (!TextUtils.isEmpty(item.text)) {
                    textView.setVisibility(View.VISIBLE);
                    textView.setText(item.text);
                } else {
                    textView.setVisibility(View.GONE);
                }

                MessageObject msgObj = item.messageObject;
                boolean hasMedia = false;
                if (msgObj != null && msgObj.messageOwner != null && msgObj.messageOwner.media != null && !(msgObj.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty)) {
                    boolean isPhoto = msgObj.isPhoto();
                    boolean isVideo = msgObj.isVideo() || msgObj.isRoundVideo() || (msgObj.getDocument() != null && MessageObject.isGifDocument(msgObj.getDocument()));
                    if (isPhoto || isVideo) {
                        hasMedia = true;
                        photoVideoContainer.setVisibility(View.VISIBLE);
                        docContainer.setVisibility(View.GONE);

                        int photoW = 0, photoH = 0;
                        if (isPhoto && !msgObj.photoThumbs.isEmpty()) {
                            TLRPC.PhotoSize sz = FileLoader.getClosestPhotoSizeWithSize(msgObj.photoThumbs, 320);
                            if (sz != null) {
                                photoW = sz.w;
                                photoH = sz.h;
                            }
                        } else if (msgObj.getDocument() != null) {
                            TLRPC.Document doc = msgObj.getDocument();
                            for (int a = 0; a < doc.attributes.size(); a++) {
                                TLRPC.DocumentAttribute attr = doc.attributes.get(a);
                                if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                                    photoW = attr.w;
                                    photoH = attr.h;
                                    break;
                                }
                            }
                        }
                        int h = AndroidUtilities.dp(160);
                        if (photoW > 0 && photoH > 0) {
                            int maxW = AndroidUtilities.displaySize.x - AndroidUtilities.dp(52);
                            h = (int) (maxW * ((float) photoH / (float) photoW));
                            if (h > AndroidUtilities.dp(240)) {
                                h = AndroidUtilities.dp(240);
                            } else if (h < AndroidUtilities.dp(120)) {
                                h = AndroidUtilities.dp(120);
                            }
                        }
                        ViewGroup.LayoutParams lp = photoVideoContainer.getLayoutParams();
                        if (lp != null && lp.height != h) {
                            lp.height = h;
                            photoVideoContainer.setLayoutParams(lp);
                        }

                        if (isVideo) {
                            playIcon.setVisibility(View.VISIBLE);
                            int duration = (int) msgObj.getDuration();
                            if (duration > 0) {
                                videoDuration.setVisibility(View.VISIBLE);
                                videoDuration.setText(AndroidUtilities.formatShortDuration((int) duration));
                            } else {
                                videoDuration.setVisibility(View.GONE);
                            }

                            TLRPC.Document doc = msgObj.getDocument();
                            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc != null ? doc.thumbs : null, 50);
                            TLRPC.PhotoSize qualityThumb = FileLoader.getClosestPhotoSizeWithSize(doc != null ? doc.thumbs : null, 320);
                            if (thumb == qualityThumb) {
                                qualityThumb = null;
                            }
                            if (msgObj.strippedThumb != null) {
                                mediaImageView.getImageReceiver().setImage(ImageLocation.getForDocument(qualityThumb, doc), null, null, null, msgObj.strippedThumb, 0, null, msgObj, 1);
                            } else {
                                mediaImageView.getImageReceiver().setImage(ImageLocation.getForDocument(qualityThumb, doc), null, ImageLocation.getForDocument(thumb, doc), "b", 0, null, msgObj, 1);
                            }
                        } else {
                            playIcon.setVisibility(View.GONE);
                            videoDuration.setVisibility(View.GONE);

                            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(msgObj.photoThumbs, 50);
                            TLRPC.PhotoSize full = FileLoader.getClosestPhotoSizeWithSize(msgObj.photoThumbs, 640, false, thumb, false);
                            if (full == thumb) {
                                thumb = null;
                            }
                            if (msgObj.strippedThumb != null) {
                                mediaImageView.getImageReceiver().setImage(ImageLocation.getForObject(full, msgObj.photoThumbsObject), null, null, null, msgObj.strippedThumb, full != null ? full.size : 0, null, msgObj, msgObj.shouldEncryptPhotoOrVideo() ? 2 : 1);
                            } else {
                                mediaImageView.getImageReceiver().setImage(ImageLocation.getForObject(full, msgObj.photoThumbsObject), null, ImageLocation.getForObject(thumb, msgObj.photoThumbsObject), "b", full != null ? full.size : 0, null, msgObj, msgObj.shouldEncryptPhotoOrVideo() ? 2 : 1);
                            }
                        }

                        photoVideoContainer.setOnClickListener(v -> openMedia(item));
                    } else if (msgObj.getDocument() != null) {
                        hasMedia = true;
                        photoVideoContainer.setVisibility(View.GONE);
                        docContainer.setVisibility(View.VISIBLE);

                        TLRPC.Document doc = msgObj.getDocument();
                        if (msgObj.isVoice()) {
                            docIcon.setImageResource(R.drawable.chat_calls_voice);
                            docTitleView.setText(LocaleController.getString(R.string.AttachAudio));
                            docSubtitleView.setText(AndroidUtilities.formatShortDuration((int) msgObj.getDuration()) + " • " + AndroidUtilities.formatFileSize(doc.size));
                        } else if (msgObj.isMusic()) {
                            docIcon.setImageResource(R.drawable.files_music);
                            String title = msgObj.getMusicTitle();
                            String author = msgObj.getMusicAuthor();
                            if (!TextUtils.isEmpty(author) && !TextUtils.isEmpty(title)) {
                                docTitleView.setText(author + " — " + title);
                            } else if (!TextUtils.isEmpty(title)) {
                                docTitleView.setText(title);
                            } else {
                                docTitleView.setText(FileLoader.getDocumentFileName(doc));
                            }
                            docSubtitleView.setText(AndroidUtilities.formatFileSize(doc.size));
                        } else {
                            docIcon.setImageResource(R.drawable.msg_round_file_s);
                            docTitleView.setText(FileLoader.getDocumentFileName(doc));
                            docSubtitleView.setText(AndroidUtilities.formatFileSize(doc.size));
                        }

                        docContainer.setOnClickListener(v -> openMedia(item));
                    }
                }

                if (hasMedia) {
                    mediaContainer.setVisibility(View.VISIBLE);
                } else {
                    mediaContainer.setVisibility(View.GONE);
                }
            }

            @Override
            public int getItemViewType(int position) {
                return position < items.size() ? 0 : 1;
            }

            @Override
            public int getItemCount() {
                return items.size() + (items.size() > 1 ? 1 : 0);
            }
        };
        return adapter;
    }
}
