package com.knte.preview.modelconfig

/** Shared, explicit size limits for the model-configuration slice. */
object ModelConfigLimits {
    const val MAX_ENDPOINT_CHARS = 512
    const val MAX_PATH_CHARS = 320
    const val MAX_MODEL_CHARS = com.knte.core.chat.ChatProfile.MAX_MODEL_CHARS
    const val MAX_SECRET_CHARS = 4096

    /** This subset speaks exactly one dialect: an OpenAI-compatible non-streaming
     * chat/completions call. The saved address is the COMPLETE request address. */
    const val REQUIRED_PATH_SUFFIX = "/chat/completions"
}
