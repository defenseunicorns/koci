package com.defenseunicorns.koci.models.content

/** UploadStatus tracks the server-side state of an upload */
data class UploadStatus(val location: String, var offset: Long, var minChunkSize: Long)
