/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hortonworks.spark.atlas.sql

import com.hortonworks.spark.atlas.sql.CommandsHarvester.HWCEntities
import org.apache.atlas.model.instance.AtlasEntity
import org.apache.spark.sql.execution.datasources.v2.WriteToDataSourceV2Exec
import org.apache.spark.sql.kafka010.atlas.KafkaHarvester


object HWCStreamingHarvester extends Harvester[WriteToDataSourceV2Exec] {
  override def harvest(node: WriteToDataSourceV2Exec, qd: QueryDetail): Seq[AtlasEntity] = {
    // source topics - can be multiple topics
    val sourceTopics = KafkaHarvester.extractSourceTopics(node)
    val inputsEntities: Seq[AtlasEntity] = KafkaHarvester.extractInputEntities(sourceTopics)

    val outputEntities = HWCEntities.getHWCEntity(node.writer)
    val logMap = KafkaHarvester.makeLogMap(sourceTopics, None, qd)

    val updatedLogMap = logMap ++ Map(
      "sparkPlanDescription" ->
      s"${logMap.get("sparkPlanDescription")}\n${qd.qe.sparkPlan.toString()}")

    KafkaHarvester.makeProcessEntities(inputsEntities, outputEntities, updatedLogMap)
  }
}
