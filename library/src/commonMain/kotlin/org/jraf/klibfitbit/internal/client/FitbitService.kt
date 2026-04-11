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
import io.ktor.client.call.body
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import org.jraf.klibfitbit.internal.json.JsonDataPoint
import org.jraf.klibfitbit.internal.json.JsonExercise
import org.jraf.klibfitbit.internal.json.JsonExercises
import org.jraf.klibfitbit.internal.json.JsonInterval
import org.jraf.klibfitbit.internal.json.JsonOAuthTokens
import org.jraf.klibfitbit.internal.json.JsonRefreshTokenResponse
import org.jraf.klibfitbit.internal.json.MetricsSummary
import org.jraf.klibfitbit.model.ExerciseType
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal class FitbitService(
  private val httpClient: HttpClient,
) {
  companion object {
    internal const val URL_BASE = "https://health.googleapis.com"
  }

  suspend fun createOAuthTokens(
    code: String,
    codeVerifier: String,
    clientId: String,
    clientSecret: String
  ): JsonOAuthTokens {
    return httpClient.post("https://oauth2.googleapis.com/token") {
      setBody(
        FormDataContent(
          Parameters.build {
            append("code", code)
            append("code_verifier", codeVerifier)
            append("grant_type", "authorization_code")
            append("client_id", clientId)
            append("redirect_uri", "http://localhost")
            append("client_secret", clientSecret)
          },
        ),
      )

      // OAuth tokens are supposed to be null when making that call - but if they're not, let's ignore them
      attributes.put(AuthCircuitBreaker, Unit)
    }.body()
  }

  suspend fun newToken(oAuthRefreshToken: String, clientId: String, clientSecret: String): JsonRefreshTokenResponse {
    return httpClient.post("https://oauth2.googleapis.com/token") {
      setBody(
        FormDataContent(
          Parameters.build {
            append("grant_type", "refresh_token")
            append("client_id", clientId)
            append("refresh_token", oAuthRefreshToken)
            append("client_secret", clientSecret)
          },
        ),
      )

      // This request is a token refresh
      attributes.put(AuthCircuitBreaker, Unit)
    }.body()
  }

  // https://dev.fitbit.com/build/reference/web-api/activity/get-activity-log-list/
  suspend fun getActivityList(startDate: String, endDate: String): JsonExercises {
    return httpClient.get("$URL_BASE/v4/users/me/dataTypes/exercise/dataPoints") {
      contentType(ContentType.Application.Json)
      parameter(
        "filter",
        "exercise.interval.civil_start_time >= $startDate AND exercise.interval.civil_start_time < $endDate",
      )
    }.body()
  }

  // https://dev.fitbit.com/build/reference/web-api/activity/create-activity-log/
  @OptIn(ExperimentalTime::class)
  suspend fun createActivity(
    exerciseType: ExerciseType,
    start: Instant,
    durationMillis: Long,
    distanceMillimeters: Int,
  ) {
    httpClient.post("$URL_BASE/v4/users/me/dataTypes/exercise/dataPoints") {
      contentType(ContentType.Application.Json)
      setBody(
        JsonDataPoint.Exercise(
          name = "",
          exercise = JsonExercise(
            interval = JsonInterval(
              startTime = start,
              startUtcOffset = start.offsetInSeconds(),
              endTime = start.plus(durationMillis.milliseconds),
              endUtcOffset = start.plus(durationMillis.milliseconds).offsetInSeconds(),
            ),
            activeDuration = "${(durationMillis / 1000)}s",
            exerciseType = exerciseType,
            displayName = "My exercise", // Doesn't matter, it's overridden by Google
            metricsSummary = MetricsSummary(
              caloriesKcal = 0f,
              distanceMillimeters = distanceMillimeters,
            ),
          ),
        ),
      )
    }
  }
}

@OptIn(ExperimentalTime::class)
private fun Instant.offsetInSeconds(): String = "${TimeZone.currentSystemDefault().offsetAt(this).totalSeconds}s"
