package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ArticleViewer;
import org.telegram.ui.iv.RichTextStyle;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class TelegraphPublisher {

    public interface Callback {
        void onSuccess(String url);
        void onError(Exception error);
    }

    private static final String PREF_NAME = "telegraph_config";
    private static final String KEY_ACCESS_TOKEN = "access_token";

    public static void publish(int currentAccount, String title, ArrayList<TL_iv.PageBlock> blocks, Callback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String token = getOrCreateAccessToken(currentAccount);
                if (TextUtils.isEmpty(token)) {
                    throw new Exception("Failed to get Telegraph access token");
                }

                String authorName = UserObject.getUserName(UserConfig.getInstance(currentAccount).getCurrentUser());
                if (TextUtils.isEmpty(authorName)) {
                    authorName = "Author";
                }

                String articleTitle = title;
                if (TextUtils.isEmpty(articleTitle)) {
                    articleTitle = extractTitle(blocks);
                }
                if (TextUtils.isEmpty(articleTitle)) {
                    articleTitle = "Article";
                }

                JSONArray content = blocksToTelegraphContent(blocks);
                if (content.length() == 0) {
                    JSONObject emptyP = new JSONObject();
                    emptyP.put("tag", "p");
                    emptyP.put("children", new JSONArray().put("..."));
                    content.put(emptyP);
                }

                JSONObject reqJson = new JSONObject();
                reqJson.put("access_token", token);
                reqJson.put("title", articleTitle);
                reqJson.put("author_name", authorName);
                reqJson.put("content", content);
                reqJson.put("return_content", false);

                JSONObject respJson = executePost("https://api.telegra.ph/createPage", reqJson);
                if (respJson != null && respJson.optBoolean("ok")) {
                    JSONObject result = respJson.optJSONObject("result");
                    if (result != null && result.has("url")) {
                        String url = result.getString("url");
                        AndroidUtilities.runOnUIThread(() -> callback.onSuccess(url));
                        return;
                    }
                }

                String err = respJson != null ? respJson.optString("error", "Unknown error") : "Empty response";
                throw new Exception("Telegraph API error: " + err);
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(e));
            }
        });
    }

    private static String getOrCreateAccessToken(int currentAccount) throws Exception {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_ACCESS_TOKEN + "_" + currentAccount, null);
        if (!TextUtils.isEmpty(token)) {
            return token;
        }

        String authorName = UserObject.getUserName(UserConfig.getInstance(currentAccount).getCurrentUser());
        if (TextUtils.isEmpty(authorName)) {
            authorName = "User";
        }
        JSONObject req = new JSONObject();
        req.put("short_name", "Telegram");
        req.put("author_name", authorName);

        JSONObject resp = executePost("https://api.telegra.ph/createAccount", req);
        if (resp != null && resp.optBoolean("ok")) {
            JSONObject res = resp.optJSONObject("result");
            if (res != null && res.has("access_token")) {
                token = res.getString("access_token");
                prefs.edit().putString(KEY_ACCESS_TOKEN + "_" + currentAccount, token).apply();
                return token;
            }
        }
        throw new Exception("Unable to create Telegraph account: " + (resp != null ? resp.optString("error") : "unknown"));
    }

    private static String extractTitle(ArrayList<TL_iv.PageBlock> blocks) {
        if (blocks == null) return null;
        for (TL_iv.PageBlock b : blocks) {
            if (ArticleViewer.isHeadingBlock(b) && b.text != null) {
                CharSequence text = RichTextStyle.toSpannable(b.text, b);
                if (!TextUtils.isEmpty(text)) return text.toString().trim();
            }
        }
        for (TL_iv.PageBlock b : blocks) {
            if (b != null && b.text != null) {
                CharSequence text = RichTextStyle.toSpannable(b.text, b);
                if (!TextUtils.isEmpty(text)) return text.toString().trim();
            }
        }
        return null;
    }

    private static JSONArray blocksToTelegraphContent(ArrayList<TL_iv.PageBlock> blocks) throws Exception {
        JSONArray content = new JSONArray();
        if (blocks == null) return content;
        for (TL_iv.PageBlock b : blocks) {
            if (b == null) continue;
            if (b instanceof TL_iv.pageBlockHeading1 || b instanceof TL_iv.pageBlockHeading2) {
                CharSequence text = RichTextStyle.toSpannable(b.text, b);
                if (!TextUtils.isEmpty(text)) {
                    JSONObject node = new JSONObject();
                    node.put("tag", "h3");
                    node.put("children", textToChildren(text));
                    content.put(node);
                }
            } else if (b instanceof TL_iv.pageBlockHeading3 || b instanceof TL_iv.pageBlockHeading4 || b instanceof TL_iv.pageBlockHeading5 || b instanceof TL_iv.pageBlockHeading6 || b instanceof TL_iv.pageBlockSubheader) {
                CharSequence text = RichTextStyle.toSpannable(b.text, b);
                if (!TextUtils.isEmpty(text)) {
                    JSONObject node = new JSONObject();
                    node.put("tag", "h4");
                    node.put("children", textToChildren(text));
                    content.put(node);
                }
            } else if (b instanceof TL_iv.pageBlockBlockquote || b instanceof TL_iv.pageBlockPullquote) {
                CharSequence text = RichTextStyle.toSpannable(b.text, b);
                if (!TextUtils.isEmpty(text)) {
                    JSONObject node = new JSONObject();
                    node.put("tag", "blockquote");
                    node.put("children", textToChildren(text));
                    content.put(node);
                }
            } else if (b instanceof TL_iv.pageBlockPreformatted) {
                CharSequence text = RichTextStyle.toSpannable(b.text, b);
                if (!TextUtils.isEmpty(text)) {
                    JSONObject node = new JSONObject();
                    node.put("tag", "pre");
                    node.put("children", new JSONArray().put(text.toString()));
                    content.put(node);
                }
            } else if (b instanceof TL_iv.pageBlockDivider) {
                JSONObject node = new JSONObject();
                node.put("tag", "hr");
                content.put(node);
            } else if (b instanceof TL_iv.pageBlockList) {
                TL_iv.pageBlockList list = (TL_iv.pageBlockList) b;
                if (list.items != null && !list.items.isEmpty()) {
                    JSONObject ul = new JSONObject();
                    ul.put("tag", "ul");
                    JSONArray itemsArray = new JSONArray();
                    for (TL_iv.PageListItem item : list.items) {
                        CharSequence it = listItemToText(item);
                        if (!TextUtils.isEmpty(it)) {
                            JSONObject li = new JSONObject();
                            li.put("tag", "li");
                            String prefix = item.checkbox ? (item.checked ? "☑ " : "☐ ") : "";
                            li.put("children", textToChildren(prefix + it));
                            itemsArray.put(li);
                        }
                    }
                    if (itemsArray.length() > 0) {
                        ul.put("children", itemsArray);
                        content.put(ul);
                    }
                }
            } else if (b instanceof TL_iv.pageBlockOrderedList) {
                TL_iv.pageBlockOrderedList list = (TL_iv.pageBlockOrderedList) b;
                if (list.items != null && !list.items.isEmpty()) {
                    JSONObject ol = new JSONObject();
                    ol.put("tag", "ol");
                    JSONArray itemsArray = new JSONArray();
                    for (TL_iv.PageListOrderedItem item : list.items) {
                        CharSequence it = orderedItemToText(item);
                        if (!TextUtils.isEmpty(it)) {
                            JSONObject li = new JSONObject();
                            li.put("tag", "li");
                            li.put("children", textToChildren(it));
                            itemsArray.put(li);
                        }
                    }
                    if (itemsArray.length() > 0) {
                        ol.put("children", itemsArray);
                        content.put(ol);
                    }
                }
            } else if (b instanceof TL_iv.pageBlockParagraph || b instanceof TL_iv.pageBlockFooter || b instanceof TL_iv.pageBlockKicker) {
                CharSequence text = RichTextStyle.toSpannable(b.text, b);
                if (!TextUtils.isEmpty(text)) {
                    JSONObject node = new JSONObject();
                    node.put("tag", "p");
                    node.put("children", textToChildren(text));
                    content.put(node);
                }
            }
        }
        return content;
    }

    private static CharSequence listItemToText(TL_iv.PageListItem item) {
        if (item instanceof TL_iv.TL_pageListItemText) {
            return RichTextStyle.toSpannable(((TL_iv.TL_pageListItemText) item).text);
        }
        return null;
    }

    private static CharSequence orderedItemToText(TL_iv.PageListOrderedItem item) {
        if (item instanceof TL_iv.TL_pageListOrderedItemText) {
            return RichTextStyle.toSpannable(((TL_iv.TL_pageListOrderedItemText) item).text);
        }
        return null;
    }

    private static JSONArray textToChildren(CharSequence cs) {
        JSONArray arr = new JSONArray();
        if (cs == null || cs.length() == 0) {
            return arr;
        }
        arr.put(cs.toString());
        return arr;
    }

    private static JSONObject executePost(String urlString, JSONObject payload) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");

        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        InputStream is = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = is.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        String responseStr = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        return new JSONObject(responseStr);
    }
}
