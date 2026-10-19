package org.json;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Iterator;

/** JVM implementation of the small Android JSON API surface used by the repeat state store. */
public class JSONObject {
    final JsonObject value;
    public JSONObject() { value = new JsonObject(); }
    JSONObject(JsonObject value) { this.value = value; }
    public JSONObject(String text) { this(JsonParser.parseString(text).getAsJsonObject()); }
    public JSONObject put(String key, Object item) {
        JsonElement element = item instanceof JSONObject ? ((JSONObject) item).value
                : item instanceof JSONArray ? ((JSONArray) item).value : new Gson().toJsonTree(item);
        value.add(key, element);
        return this;
    }
    public long getLong(String key) { return value.get(key).getAsLong(); }
    public int getInt(String key) { return value.get(key).getAsInt(); }
    public String getString(String key) { return value.get(key).getAsString(); }
    public JSONObject getJSONObject(String key) { return new JSONObject(value.getAsJsonObject(key)); }
    public long optLong(String key) { return value.has(key) ? getLong(key) : 0; }
    public int optInt(String key) { return value.has(key) ? getInt(key) : 0; }
    public String optString(String key) { return value.has(key) ? getString(key) : ""; }
    public boolean optBoolean(String key) { return value.has(key) && value.get(key).getAsBoolean(); }
    public Iterator<String> keys() { return value.keySet().iterator(); }
    public String toString() { return value.toString(); }
}
