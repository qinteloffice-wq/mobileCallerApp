package com.qintel.android.caller

import kotlinx.serialization.Serializable

@Serializable
data class WorkItem(
    val callSequance: String? = null,
    val fileName: String? = null,
    val recordingDuration: Int? = null,
    val simCardName: String? = null
) : java.io.Serializable // Make it Serializable for Intent passing
