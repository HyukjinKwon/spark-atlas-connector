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

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.{Files, Path}
import java.util.Locale

import com.hortonworks.spark.atlas.sql.testhelper.{AtlasQueryExecutionListener, CreateEntitiesTrackingAtlasClient, DirectProcessSparkExecutionPlanProcessor, KafkaTopicEntityValidator}
import com.hortonworks.spark.atlas.types.{external, metadata}
import com.hortonworks.spark.atlas.utils.SparkUtils
import com.hortonworks.spark.atlas.AtlasClientConf
import com.hortonworks.spark.atlas.sql.streaming.KafkaTopicInformation

import org.apache.atlas.model.instance.AtlasEntity
import org.apache.commons.io.{FileUtils, IOUtils}
import org.apache.spark.sql.kafka010.KafkaTestUtils
import org.apache.spark.sql.streaming.StreamTest

class SparkExecutionPlanProcessorForBatchQuerySuite
  extends StreamTest
  with KafkaTopicEntityValidator {
  import com.hortonworks.spark.atlas.sql.testhelper.AtlasEntityReadHelper._

  val brokerProps: Map[String, Object] = Map[String, Object]()
  var kafkaTestUtils: KafkaTestUtils = _

  val atlasClientConf: AtlasClientConf = new AtlasClientConf()
    .set(AtlasClientConf.CHECK_MODEL_IN_START.key, "false")
  var atlasClient: CreateEntitiesTrackingAtlasClient = _
  val testHelperQueryListener = new AtlasQueryExecutionListener()

  override def beforeAll(): Unit = {
    super.beforeAll()
    kafkaTestUtils = new KafkaTestUtils(brokerProps)
    kafkaTestUtils.setup()
    atlasClient = new CreateEntitiesTrackingAtlasClient()
    testHelperQueryListener.clear()
    spark.listenerManager.register(testHelperQueryListener)
  }

  override def afterAll(): Unit = {
    if (kafkaTestUtils != null) {
      kafkaTestUtils.teardown()
      kafkaTestUtils = null
    }
    atlasClient = null
    spark.listenerManager.unregister(testHelperQueryListener)
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    atlasClient.clearEntities()
  }

  test("Read csv file and save as table") {
    val planProcessor = new DirectProcessSparkExecutionPlanProcessor(atlasClient, atlasClientConf)

    val csvContent = Seq("a,1", "b,2", "c,3", "d,4").mkString("\n")
    val tempFile: Path = writeCSVtextToTempFile(csvContent)

    val rand = new scala.util.Random()
    val outputTableName = "app_details_" + rand.nextInt(1000000000)

    val df = spark.read.csv(tempFile.toAbsolutePath.toString)
    df.write.saveAsTable(outputTableName)

    val queryDetail = testHelperQueryListener.queryDetails.last
    planProcessor.process(queryDetail)
    val entities = atlasClient.createdEntities

    val tableEntity: AtlasEntity = getOnlyOneEntity(entities, metadata.TABLE_TYPE_STRING)
    assertTableEntity(tableEntity, outputTableName)
    assertSchemaEntities(tableEntity, entities)

    // we're expecting three file system entities:
    // one for input file, one for database warehouse, and one for output table (under
    // database warehouse since it's a managed table).
    val fsEntities = listAtlasEntitiesAsType(entities, external.FS_PATH_TYPE_STRING)
    assert(fsEntities.size === 3)

    val databaseEntity = getOnlyOneEntity(entities, metadata.DB_TYPE_STRING)
    assertDatabaseEntity(databaseEntity, tableEntity, fsEntities, outputTableName)

    val databaseLocationFsEntity = getAtlasEntityAttribute(databaseEntity, "locationUri")

    val inputFsEntities = fsEntities.filterNot(_ == databaseLocationFsEntity)
    assert(inputFsEntities.size === 2)

    // input file
    // this code asserts on runtime that one of fs entity matches against source path
    val sourcePath = tempFile.toAbsolutePath.toString
    val inputFsEntity = inputFsEntities.find { p =>
      getStringAttribute(p, "name").toLowerCase(Locale.ROOT) == sourcePath.toLowerCase(Locale.ROOT)
    }.get
    assertInputFsEntity(inputFsEntity, sourcePath)

    // storage description
    val storageEntity = getOnlyOneEntity(entities, metadata.STORAGEDESC_TYPE_STRING)
    assertStorageDefinitionEntity(storageEntity, tableEntity)

    val locationEntity = getAtlasEntityAttribute(storageEntity, "locationUri")
    assert(getStringAttribute(locationEntity, "path").startsWith(
      getStringAttribute(databaseLocationFsEntity, "path")))

    // check for 'spark_process'
    val processEntity = getOnlyOneEntity(entities, metadata.PROCESS_TYPE_STRING)

    val inputs = getSeqAtlasEntityAttribute(processEntity, "inputs")
    val outputs = getSeqAtlasEntityAttribute(processEntity, "outputs")

    val input = getOnlyOneEntity(inputs, external.FS_PATH_TYPE_STRING)
    val output = getOnlyOneEntity(outputs, metadata.TABLE_TYPE_STRING)

    // input/output in 'spark_process' should be same as outer entities
    assert(input === inputFsEntity)
    assert(output === tableEntity)

    val expectedMap = Map(
      "executionId" -> queryDetail.executionId.toString,
      "remoteUser" -> SparkUtils.currSessionUser(queryDetail.qe),
      "executionTime" -> queryDetail.executionTime.toString,
      "details" -> queryDetail.qe.toString()
    )

    expectedMap.foreach { case (key, value) =>
      assert(processEntity.getAttribute(key) === value)
    }
  }

  test("Create external table against JSON files") {
    val planProcessor = new DirectProcessSparkExecutionPlanProcessor(atlasClient, atlasClientConf)

    val tempDirPath = writeJsonFilesToTempDirectory()
    val tempDirPathStr = tempDirPath.toFile.getAbsolutePath

    val rand = new scala.util.Random()
    val outputTableName = "test_json_" + rand.nextInt(1000000000)
    spark.sql(s"CREATE TABLE $outputTableName USING json location '$tempDirPathStr'")

    val queryDetail = testHelperQueryListener.queryDetails.last
    planProcessor.process(queryDetail)

    val entities = atlasClient.createdEntities

    val tableEntity: AtlasEntity = getOnlyOneEntity(entities, metadata.TABLE_TYPE_STRING)
    assertTableEntity(tableEntity, outputTableName)
    assertSchemaEntities(tableEntity, entities)

    // we're expecting two file system entities:
    // one for input file, one for database warehouse.
    val fsEntities = listAtlasEntitiesAsType(entities, external.FS_PATH_TYPE_STRING)
    assert(fsEntities.size === 2)

    // database
    val databaseEntity: AtlasEntity = getOnlyOneEntity(entities, metadata.DB_TYPE_STRING)
    assertDatabaseEntity(databaseEntity, tableEntity, fsEntities, outputTableName)

    val databaseLocationFsEntity = getAtlasEntityAttribute(databaseEntity, "locationUri")

    // fs
    val inputFsEntities = fsEntities.filterNot(_ == databaseLocationFsEntity)
    assert(inputFsEntities.size === 1)

    // input file
    val inputFsEntity = inputFsEntities.find { p =>
      getStringAttribute(p, "name").toLowerCase(Locale.ROOT) ==
        tempDirPathStr.toLowerCase(Locale.ROOT)
    }.get

    assertInputFsEntity(inputFsEntity, tempDirPathStr)

    // storage description
    val storageEntity = getOnlyOneEntity(entities, metadata.STORAGEDESC_TYPE_STRING)
    assertStorageDefinitionEntity(storageEntity, tableEntity)

    assert(getAtlasEntityAttribute(storageEntity, "locationUri") === inputFsEntity)
  }

  test("Save Spark table to Kafka via df.save()") {
    val planProcessor = new DirectProcessSparkExecutionPlanProcessor(atlasClient, atlasClientConf)

    val rand = new scala.util.Random()
    val inputTableName = "test_spark_table_" + rand.nextInt(1000000000)
    val outputTopicName = "test_spark_topic_" + rand.nextInt(1000000000)

    val csvContent = Seq("a,1", "b,2", "c,3", "d,4").mkString("\n")
    val tempFile: Path = writeCSVtextToTempFile(csvContent)

    val df = spark.read.csv(tempFile.toAbsolutePath.toString)
    df.write.saveAsTable(inputTableName)

    // we don't want to check above queries, so reset the entities in listener
    testHelperQueryListener.clear()

    val customClusterName = "customCluster"

    spark
      .sql(s"select * from $inputTableName")
      .selectExpr("cast(_c0 as String) AS value")
      .write
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaTestUtils.brokerAddress)
      .option("topic", outputTopicName)
      .option("kafka." + AtlasClientConf.CLUSTER_NAME.key, customClusterName)
      .save()

    val queryDetail = testHelperQueryListener.queryDetails.last
    planProcessor.process(queryDetail)

    val entities = atlasClient.createdEntities

    // We already have validations for table-relevant entities in other UTs,
    // so minimize validation here.

    val tableEntity: AtlasEntity = getOnlyOneEntity(entities, metadata.TABLE_TYPE_STRING)
    assertTableEntity(tableEntity, inputTableName)
    assertSchemaEntities(tableEntity, entities)

    // kafka topic
    val outputKafkaEntity = getOnlyOneEntity(entities, external.KAFKA_TOPIC_STRING)
    val expectedTopics = Seq(
      KafkaTopicInformation(outputTopicName, Some(customClusterName))
    )
    assertEntitiesKafkaTopicType(expectedTopics, entities.toSet)

    // check for 'spark_process'
    val processEntity = getOnlyOneEntity(entities, metadata.PROCESS_TYPE_STRING)

    val inputs = getSeqAtlasEntityAttribute(processEntity, "inputs")
    val outputs = getSeqAtlasEntityAttribute(processEntity, "outputs")

    val input = getOnlyOneEntity(inputs, metadata.TABLE_TYPE_STRING)
    val output = getOnlyOneEntity(outputs, external.KAFKA_TOPIC_STRING)

    // input/output in 'spark_process' should be same as outer entities
    assert(input === tableEntity)
    assert(output === outputKafkaEntity)

    val expectedMap = Map(
      "executionId" -> queryDetail.executionId.toString,
      "remoteUser" -> SparkUtils.currSessionUser(queryDetail.qe),
      "executionTime" -> queryDetail.executionTime.toString,
      "details" -> queryDetail.qe.toString()
    )

    expectedMap.foreach { case (key, value) =>
      assert(processEntity.getAttribute(key) === value)
    }
  }

  private def writeCSVtextToTempFile(csvContent: String) = {
    val tempFile = Files.createTempFile("spark-atlas-connector-csv-temp", ".csv")

    // remove temporary file in shutdown
    org.apache.hadoop.util.ShutdownHookManager.get().addShutdownHook(
      new Runnable {
        override def run(): Unit = {
          Files.deleteIfExists(tempFile)
        }
      }, 10)

    val bw = new BufferedWriter(new FileWriter(tempFile.toFile))
    bw.write(csvContent)
    bw.close()
    tempFile
  }

  private def writeJsonFilesToTempDirectory(): Path = {
    val tempParentDir = Files.createTempDirectory("spark-atlas-connector-json-temp")
    val tempDir = new File(tempParentDir.toFile, "json")
    val tempDirPath = tempDir.getAbsolutePath

    org.apache.hadoop.util.ShutdownHookManager.get().addShutdownHook(
      new Runnable {
        override def run(): Unit = {
          FileUtils.deleteQuietly(tempParentDir.toFile)
        }
      }, 10)

    spark.range(10).write.json(tempDirPath)

    tempDir.toPath
  }

  private def assertTableEntity(tableEntity: AtlasEntity, tableName: String): Unit = {
    // only assert qualifiedName and skip assertion on database, and attributes on database
    // they should be covered in other UT
    val tableQualifiedName = getStringAttribute(tableEntity, "qualifiedName")
    assert(tableQualifiedName.endsWith(tableName))
  }

  private def assertSchemaEntities(tableEntity: AtlasEntity, entities: Seq[AtlasEntity]): Unit = {
    if (atlasClientConf.get(AtlasClientConf.ATLAS_SPARK_COLUMN_ENABLED).toBoolean) {
      val columnEntities = listAtlasEntitiesAsType(entities, metadata.COLUMN_TYPE_STRING)
      val columnEntitiesInTableAttribute = getSeqAtlasEntityAttribute(tableEntity, "spark_schema")
      assert(Set(columnEntities) === Set(columnEntitiesInTableAttribute))
    } else {
      assert(!tableEntity.getAttributes.containsKey("spark_schema"))
      assert(listAtlasEntitiesAsType(entities, metadata.COLUMN_TYPE_STRING).isEmpty)
    }
  }

  private def assertDatabaseEntity(
      databaseEntity: AtlasEntity,
      tableEntity: AtlasEntity,
      fsEntities: Seq[AtlasEntity],
      tableName: String)
    : Unit = {
    val tableQualifiedName = getStringAttribute(tableEntity, "qualifiedName")
    // remove table name + '.' prior to table name
    val databaseQualifiedName = tableQualifiedName.substring(0,
      tableQualifiedName.indexOf(tableName) - 1)

    assert(getStringAttribute(databaseEntity, "qualifiedName") === databaseQualifiedName)

    // database entity in table entity should be same as outer database entity
    val databaseEntityInTable = getAtlasEntityAttribute(tableEntity, "db")
    assert(databaseEntity === databaseEntityInTable)

    val databaseLocationFsEntity = getAtlasEntityAttribute(databaseEntity, "locationUri")

    // database warehouse
    assert(fsEntities.contains(databaseLocationFsEntity))
  }

  private def assertInputFsEntity(fsEntity: AtlasEntity, sourcePath: String): Unit = {
    assertPathsEquals(getStringAttribute(fsEntity, "name"), sourcePath)
    assertPathsEquals(getStringAttribute(fsEntity, "path"), sourcePath)
    assertPathsEquals(getStringAttribute(fsEntity, "qualifiedName"),
      "file://" + sourcePath)
  }

  private def assertStorageDefinitionEntity(sdEntity: AtlasEntity, tableEntity: AtlasEntity)
    : Unit = {
    val tableQualifiedName = getStringAttribute(tableEntity, "qualifiedName")
    val storageQualifiedName = tableQualifiedName + ".storageFormat"
    assert(getStringAttribute(sdEntity, "qualifiedName") === storageQualifiedName)

    val storageEntityInTableAttribute = getAtlasEntityAttribute(tableEntity, "sd")
    assert(sdEntity === storageEntityInTableAttribute)
  }

  private def assertPathsEquals(path1: String, path2: String): Unit = {
    // we are comparing paths with lower case, since we may want to run the test
    // from case-insensitive filesystem
    assert(path1.toLowerCase(Locale.ROOT) === path2.toLowerCase(Locale.ROOT))
  }
}
