package com.example.orientar.network

data class ChatResponse(
    val answer: String,
    val confidence: Double,
    val context_used: List<String>? = null,
    val latency_ms: Int? = null,
    val domain_score: Double? = null,
    val in_domain: Boolean? = null
)