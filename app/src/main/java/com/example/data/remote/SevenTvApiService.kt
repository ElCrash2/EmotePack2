package com.example.data.remote

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

interface SevenTvApiService {

    @POST("v3/gql")
    suspend fun queryGraphQlV3(@Body body: RequestBody): Response<ResponseBody>

    @POST("v4/gql")
    suspend fun queryGraphQlV4(@Body body: RequestBody): Response<ResponseBody>

    @GET("v3/users/{id}")
    suspend fun getUserById(@Path("id") userId: String): Response<ResponseBody>

    @GET("v3/emote-sets/{id}")
    suspend fun getEmoteSetById(@Path("id") setId: String): Response<ResponseBody>

    @GET("v3/emotes/{id}")
    suspend fun getEmoteById(@Path("id") emoteId: String): Response<ResponseBody>

    @GET
    suspend fun downloadFile(@Url url: String): Response<ResponseBody>
}
