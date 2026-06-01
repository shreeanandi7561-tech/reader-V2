package com.reader.app.domain.model

/**
 * One image attached to a multimodal LLM call.
 *
 * Provider-agnostic: the same [ImageData] is repackaged as
 * `inline_data` for Gemini and (in future) `image_url` for OpenAI-vision.
 *
 * @property mimeType e.g. `image/jpeg`, `image/png`. Gemini's docs accept
 *   `image/jpeg`, `image/png`, `image/webp`, `image/heic`, `image/heif`.
 * @property base64   raw base64-encoded bytes — NO `data:image/...;base64,`
 *   prefix. Encoded with [android.util.Base64.NO_WRAP] (no line breaks)
 *   so it can be dropped into the JSON body verbatim.
 * @property captionTimestampSec optional playback-time hint (in seconds
 *   from the start of the source video) so the prompt builder can label
 *   each frame with its `mm:ss`. Null when the image isn't from a
 *   timestamped source.
 */
data class ImageData(
    val mimeType: String,
    val base64: String,
    val captionTimestampSec: Double? = null
)
