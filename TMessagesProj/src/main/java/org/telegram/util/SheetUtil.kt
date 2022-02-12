package org.telegram.util

import com.blankj.utilcode.util.GsonUtils
import com.google.gson.GsonBuilder
import okhttp3.*
import org.telegram.messenger.BuildVars
import java.util.concurrent.TimeUnit


object SheetUtil {
    private val client = OkHttpClient().newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    @JvmStatic
    fun read(sheetUrl: String): List<List<String>> {
        val request = Request.Builder().url(
            HttpUrl.parse(BuildVars.SHEETDB_URL)!!
                .newBuilder()
                .addQueryParameter("action", "read")
                .addQueryParameter("sheet_url", sheetUrl).build()
        )
            .build()
        val response: Response = client.newCall(request).execute()

        val result = GsonUtils.fromJson(
            response.body()!!.string(),
            SheetResponse::class.java
        )

        return result.data
    }

    fun write(dataSheetUrl: String, values: ArrayList<List<String>>): Boolean {
        val body: RequestBody = RequestBody.create(
            MediaType.parse("application/json"), GsonUtils.toJson(values)
        )

        val request = Request.Builder().url(
            HttpUrl.parse(BuildVars.SHEETDB_URL)!!
                .newBuilder()
                .addQueryParameter("action", "write")
                .addQueryParameter("sheet_url", dataSheetUrl).build()
        ).post(body)
            .build()
        val response: Response = client.newCall(request).execute()
        return response.isSuccessful
    }

    data class SheetResponse(
        val data: List<List<String>>
    )

}