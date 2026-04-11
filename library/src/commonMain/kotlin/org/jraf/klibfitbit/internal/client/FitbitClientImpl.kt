/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2025-present Benoit 'BoD' Lubek (BoD@JRAF.org)
 * and contributors (https://github.com/BoD/klibfitbit/graphs/contributors)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jraf.klibfitbit.internal.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import org.jraf.klibfitbit.client.FitbitClient
import org.jraf.klibfitbit.client.configuration.ClientConfiguration
import org.jraf.klibfitbit.client.configuration.HttpLoggingLevel
import org.jraf.klibfitbit.client.configuration.OAuthTokens
import org.jraf.klibfitbit.internal.json.JsonDataPoint
import org.jraf.klibfitbit.internal.json.JsonExercise
import org.jraf.klibfitbit.model.Activity
import org.jraf.klibfitbit.model.ExerciseType
import org.jraf.klibfitbit.model.OAuthAuthorizationUrlResult
import org.jraf.klibnanolog.logd
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal class FitbitClientImpl(
  private var clientConfiguration: ClientConfiguration,
  private val onOAuthTokensRenewed: suspend (newOAuthTokens: OAuthTokens) -> Unit,
) : FitbitClient {
  private val service: FitbitService by lazy {
    FitbitService(provideHttpClient())
  }

  private fun provideHttpClient(): HttpClient {
    return HttpClient {
      install(ContentNegotiation) {
        json(
          Json {
            ignoreUnknownKeys = true
            useAlternativeNames = false
          },
        )
      }
      install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
      }
      engine {
        // Set up a proxy if requested
        clientConfiguration.httpConfiguration.httpProxy?.let { httpProxy ->
          proxy = ProxyBuilder.http(
            URLBuilder().apply {
              host = httpProxy.host
              port = httpProxy.port
            }.build(),
          )
        }
      }
      install(Auth) {
        bearer {
          loadTokens {
            clientConfiguration.oAuthTokens?.let { oAuthTokens ->
              BearerTokens(
                accessToken = oAuthTokens.accessToken,
                refreshToken = oAuthTokens.refreshToken,
              )
            }
          }

          refreshTokens {
            val refreshTokenResponse = service.newToken(
              oAuthRefreshToken = clientConfiguration.oAuthTokens!!.refreshToken,
              clientId = clientConfiguration.clientId,
              clientSecret = clientConfiguration.clientSecret,
            )
            val oAuthTokens = OAuthTokens(
              accessToken = refreshTokenResponse.access_token,
              refreshToken = clientConfiguration.oAuthTokens!!.refreshToken,
            )
            // Update client configuration with new tokens
            clientConfiguration = clientConfiguration.copy(
              oAuthTokens = oAuthTokens,
            )

            // Inform the client that new tokens are available
            onOAuthTokensRenewed(oAuthTokens)

            BearerTokens(
              accessToken = oAuthTokens.accessToken,
              refreshToken = oAuthTokens.refreshToken,
            )
          }
        }
      }

      // Setup logging if requested
      if (clientConfiguration.httpConfiguration.loggingLevel != HttpLoggingLevel.NONE) {
        install(Logging) {
          logger = object : Logger {
            override fun log(message: String) {
              logd(message)
            }
          }
          level = when (clientConfiguration.httpConfiguration.loggingLevel) {
            HttpLoggingLevel.NONE -> LogLevel.NONE
            HttpLoggingLevel.INFO -> LogLevel.INFO
            HttpLoggingLevel.HEADERS -> LogLevel.HEADERS
            HttpLoggingLevel.BODY -> LogLevel.BODY
            HttpLoggingLevel.ALL -> LogLevel.ALL
          }
        }
      }
    }
  }

  override fun oAuthCreateAuthorizationUrl(scopes: List<String>): OAuthAuthorizationUrlResult {
    // See https://dev.fitbit.com/build/reference/web-api/developer-guide/authorization/
    val randomLetterList: List<Char> = (1..Random.nextInt(43..128))
      .map { Random.nextInt(from = 'a'.code, until = 'z'.code).toChar() }
    val codeVerifier = randomLetterList.toCharArray().concatToString()

    // A SHA-256 hash of the code verifier, base64url encoded with padding omitted, called the code challenge
    val codeChallenge = randomLetterList.map { it.code.toByte() }.toByteArray().toByteString().sha256().base64Url().removeSuffix("=")

    val url = URLBuilder("https://accounts.google.com/o/oauth2/v2/auth").apply {
      parameters.apply {
        append("client_id", clientConfiguration.clientId)
        append("response_type", "code")
        append("scope", scopes.joinToString(" "))
        append("code_challenge_method", "S256")
        append("code_challenge", codeChallenge)
        append("redirect_uri", "http://localhost")
      }
    }.buildString()

    return OAuthAuthorizationUrlResult(
      authorizeUrl = url,
      codeVerifier = codeVerifier,
    )
  }

  override suspend fun oAuthFetchTokens(
    oAuthAuthorizationUrlResult: OAuthAuthorizationUrlResult,
    authorizationCallbackUrl: String,
  ) {
    val url = Url(authorizationCallbackUrl)
    val code: String = url.parameters["code"] ?: throw IllegalArgumentException("No code parameter in callback URL")
    val jsonOAuthTokens = service.createOAuthTokens(
      code = code,
      codeVerifier = oAuthAuthorizationUrlResult.codeVerifier,
      clientId = clientConfiguration.clientId,
      clientSecret = clientConfiguration.clientSecret,
    )
    val oAuthTokens = OAuthTokens(
      accessToken = jsonOAuthTokens.access_token,
      refreshToken = jsonOAuthTokens.refresh_token,
    )

    // Update client configuration with new tokens
    clientConfiguration = clientConfiguration.copy(
      oAuthTokens = oAuthTokens,
    )

    // Inform the client that new tokens are available
    onOAuthTokensRenewed(oAuthTokens)
  }

  @OptIn(FormatStringsInDatetimeFormats::class)
  override suspend fun getActivityList(date: LocalDate): List<Activity> {
    val dateTimeFormat = LocalDate.Format {
      byUnicodePattern("yyyy-MM-dd")
    }
    val startDateString = dateTimeFormat.format(date)
    val endDate = date.plus(1, DateTimeUnit.DAY)
    val endDateString = dateTimeFormat.format(endDate)

    val jsonActivityPage = service.getActivityList(startDateString, endDateString)
    return jsonActivityPage.dataPoints.map { it.toActivity() }
  }

  @OptIn(FormatStringsInDatetimeFormats::class, ExperimentalTime::class)
  override suspend fun createActivity(
    exerciseType: ExerciseType,
    start: Instant,
    duration: Duration,
    distanceMeters: Double,
  ) {
    service.createActivity(
      exerciseType = exerciseType,
      start = start,
      durationMillis = duration.inWholeMilliseconds,
      distanceMillimeters = (distanceMeters * 1000).toInt(),
    )
  }
}

private fun JsonExercise.activityTypeId(): Int {
  return when (this.exerciseType) {
    ExerciseType.WALKING -> 91064
    ExerciseType.BIKING -> 90001
    else -> 99999
  }
}

@OptIn(ExperimentalTime::class)
private fun JsonDataPoint.Exercise.toActivity(): Activity {
  return Activity(
    id = this.name.substringAfterLast("/"),
    activityName = this.exercise.displayName,
    activityTypeId = this.exercise.activityTypeId().toString(),
    calories = this.exercise.metricsSummary.caloriesKcal.toInt(),
    // Duration is like "123s"
    duration = Duration.parse(this.exercise.activeDuration),
    startTime = this.exercise.interval.startTime,
    distanceMeters = this.exercise.metricsSummary.distanceMillimeters / 1000.0,
  )
}
