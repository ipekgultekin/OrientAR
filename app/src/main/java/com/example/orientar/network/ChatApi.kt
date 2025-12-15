package com.example.orientar.network

import retrofit2.http.Body
import retrofit2.http.POST

interface ChatApi {

    @POST("chatbot/query")
    suspend fun askQuestion(
        @Body request: ChatRequest
    ): ChatResponse
}
