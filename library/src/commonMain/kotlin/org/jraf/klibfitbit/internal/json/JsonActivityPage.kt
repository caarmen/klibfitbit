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

package org.jraf.klibfitbit.internal.json

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.jraf.klibfitbit.model.ExerciseType
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class JsonInterval @OptIn(ExperimentalTime::class) constructor(
  val startTime: Instant,
  val startUtcOffset: String,
  val endTime: Instant,
  val endUtcOffset: String,
)

@Serializable
data class MetricsSummary(
  val caloriesKcal: Float,
  val distanceMillimeters: Int = 0,
)

@Serializable
data class JsonExercise(
  val interval: JsonInterval,
  val activeDuration: String,
  val exerciseType: ExerciseType,
  val displayName: String,
  val metricsSummary: MetricsSummary,
)

@Serializable
data class JsonDistance(
  val millimeters: Int,
  val interval: JsonInterval,
)

@Serializable(with = DataPointSerializer::class)
sealed class JsonDataPoint {
  /**
   * A DataPoint can have either an exercise or a distance attribute, but not both.
   * See https://developers.google.com/health/reference/rest/v4/users.dataTypes.dataPoints#DataPoint
   * Other types are possible too, but we only support these two for now.
   */
  abstract val name: String

  @Serializable
  data class Exercise(
    override val name: String,
    val exercise: JsonExercise,
  ) : JsonDataPoint()

  @Serializable
  data class Distance(
    override val name: String,
    val distance: JsonDistance,
  ) : JsonDataPoint()
}

object DataPointSerializer : JsonContentPolymorphicSerializer<JsonDataPoint>(JsonDataPoint::class) {
  override fun selectDeserializer(element: JsonElement): DeserializationStrategy<JsonDataPoint> {
    return when {
      "exercise" in element.jsonObject -> JsonDataPoint.Exercise.serializer()
      "distance" in element.jsonObject -> JsonDataPoint.Distance.serializer()
      else -> error("Unhandled class $element")
    }
  }
}

@Serializable
data class JsonExercises(
  val dataPoints: List<JsonDataPoint.Exercise> = emptyList(),
)
