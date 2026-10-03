package dev.dotnote.app

import org.json.JSONObject

// Compatibility adapters for Android callers; the format and parser live in shared.
fun DocumentCodec.itemJson(item: Item): JSONObject = JSONObject(encode(Document(listOf(item)))).getJSONArray("items").getJSONObject(0)
fun DocumentCodec.decode(value: JSONObject): Document = decode(value.toString())
fun DocumentCodec.validate(value: JSONObject, onAsset: (String) -> Unit = {}) = validate(value.toString(), onAsset)
