package org.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

public class JSONArray {
    final JsonArray value;
    public JSONArray() { value = new JsonArray(); }
    public JSONArray(String text) { value = JsonParser.parseString(text).getAsJsonArray(); }
    public JSONArray put(JSONObject item) { value.add(item.value); return this; }
    public int length() { return value.size(); }
    public JSONObject getJSONObject(int index) { return new JSONObject(value.get(index).getAsJsonObject()); }
    public String toString() { return value.toString(); }
}
