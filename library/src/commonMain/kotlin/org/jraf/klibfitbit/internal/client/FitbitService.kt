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
import org.jraf.klibfitbit.internal.json.JsonActivityPage
import org.jraf.klibfitbit.internal.json.JsonOAuthTokens
import org.jraf.klibfitbit.internal.json.JsonRefreshTokenResponse

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
  suspend fun getActivityList(afterDate: String): JsonActivityPage {
    return httpClient.get("$URL_BASE/1/user/-/activities/list.json") {
      contentType(ContentType.Application.Json)
      parameter("afterDate", afterDate)
      parameter("sort", "desc")
      parameter("offset", "0")
      parameter("limit", "50")
    }.body()
  }

  // https://dev.fitbit.com/build/reference/web-api/activity/create-activity-log/
  suspend fun createActivity(
    activityId: Long,
    startTime: String,
    durationMillis: Long,
    date: String,
    distance: Double,
    distanceUnit: String,
  ) {
    httpClient.post("$URL_BASE/1/user/-/activities.json") {
      contentType(ContentType.Application.Json)
      parameter("activityId", activityId)
      parameter("startTime", startTime)
      parameter("durationMillis", durationMillis)
      parameter("date", date)
      parameter("distance", distance)
      parameter("distanceUnit", distanceUnit)
    }
  }
}
