package com.example.orientar.network

data class ChatResponse(
    val message: String,
    val confidence: Double,
    val context_used: List<String>? = null
)
