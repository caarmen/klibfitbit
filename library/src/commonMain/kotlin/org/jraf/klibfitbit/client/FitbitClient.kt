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

package org.jraf.klibfitbit.client

import kotlinx.datetime.LocalDate
import org.jraf.klibfitbit.client.configuration.ClientConfiguration
import org.jraf.klibfitbit.client.configuration.OAuthTokens
import org.jraf.klibfitbit.internal.client.FitbitClientImpl
import org.jraf.klibfitbit.model.Activity
import org.jraf.klibfitbit.model.ExerciseType
import org.jraf.klibfitbit.model.OAuthAuthorizationUrlResult
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface FitbitClient {
  companion object {
    fun newInstance(
      configuration: ClientConfiguration,
      onOAuthTokensRenewed: suspend (newOAuthTokens: OAuthTokens) -> Unit,
    ): FitbitClient = FitbitClientImpl(configuration, onOAuthTokensRenewed)
  }

  fun oAuthCreateAuthorizationUrl(scopes: List<String>): OAuthAuthorizationUrlResult

  suspend fun oAuthFetchTokens(
    oAuthAuthorizationUrlResult: OAuthAuthorizationUrlResult,
    authorizationCallbackUrl: String,
  )

  suspend fun getActivityList(date: LocalDate): List<Activity>

  @OptIn(ExperimentalTime::class)
  suspend fun createActivity(
    exerciseType: ExerciseType,
    start: Instant,
    duration: Duration,
    distanceMeters: Double,
  )
}
