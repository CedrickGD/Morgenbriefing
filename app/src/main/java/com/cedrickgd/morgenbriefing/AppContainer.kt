package com.cedrickgd.morgenbriefing

import android.content.Context
import com.cedrickgd.morgenbriefing.data.AppJson
import com.cedrickgd.morgenbriefing.data.BriefRepository
import com.cedrickgd.morgenbriefing.data.EmailSettingsRepository
import com.cedrickgd.morgenbriefing.data.SettingsRepository
import com.cedrickgd.morgenbriefing.data.WeatherRepository
import com.cedrickgd.morgenbriefing.data.local.BriefStore
import com.cedrickgd.morgenbriefing.data.remote.GitHubBriefSource
import com.cedrickgd.morgenbriefing.data.remote.GitHubConfigStore
import com.cedrickgd.morgenbriefing.data.remote.OpenMeteoClient
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency graph; one instance per process, owned by [MorgenbriefingApp]. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val json = AppJson

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val settings = SettingsRepository(app)
    val store = BriefStore(File(app.filesDir, "store"), json)
    val openMeteo = OpenMeteoClient(http, json)
    val github = GitHubBriefSource(
        http, json,
        owner = BuildConfig.BRIEF_OWNER,
        repo = BuildConfig.BRIEF_REPO,
        branch = BuildConfig.BRIEF_BRANCH,
    )
    val weather = WeatherRepository(openMeteo, store)
    val briefs = BriefRepository(app, github, store, settings, json)
    val emailSettings = EmailSettingsRepository(
        remote = GitHubConfigStore(http, json, owner = BuildConfig.BRIEF_OWNER, repo = BuildConfig.BRIEF_REPO),
        tokenProvider = settings::token,
    )
}

val Context.container: AppContainer
    get() = (applicationContext as MorgenbriefingApp).container

val Context.ai: com.cedrickgd.morgenbriefing.ai.AiServices
    get() = (applicationContext as MorgenbriefingApp).ai

val Context.listen: com.cedrickgd.morgenbriefing.listen.ListenController
    get() = (applicationContext as MorgenbriefingApp).listen
