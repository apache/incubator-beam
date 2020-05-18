/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.beam.learning.katas.coretransforms.combine.combineperkey

import org.apache.beam.learning.katas.coretransforms.combine.combineperkey.Task.applyTransform
import org.apache.beam.sdk.testing.PAssert
import org.apache.beam.sdk.testing.TestPipeline
import org.apache.beam.sdk.transforms.Create
import org.apache.beam.sdk.values.KV
import org.junit.Rule
import org.junit.Test

class TaskTest {

    @Rule
    @Transient
    private val testPipeline = TestPipeline.create()

    @Test
    fun `Core Transforms - Combine - Combine PerKey`() {
        val values = Create.of(
                KV.of(Task.PLAYER_1, 15), KV.of(Task.PLAYER_2, 10), KV.of(Task.PLAYER_1, 100),
                KV.of(Task.PLAYER_3, 25), KV.of(Task.PLAYER_2, 75)
        )
        val numbers = testPipeline.apply(values)

        val results = applyTransform(numbers)

        PAssert.that(results).containsInAnyOrder(
                KV.of(Task.PLAYER_1, 115), KV.of(Task.PLAYER_2, 85), KV.of(Task.PLAYER_3, 25)
        )

        testPipeline.run().waitUntilFinish()
    }

}