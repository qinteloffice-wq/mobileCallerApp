package com.qintel.android.caller

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SimData(@SerialName("simCards") val simCards: List<String>)
