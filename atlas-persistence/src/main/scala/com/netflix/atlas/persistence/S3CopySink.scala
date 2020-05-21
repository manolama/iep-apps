/*
 * Copyright 2014-2020 Netflix, Inc.
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
package com.netflix.atlas.persistence

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.Attributes
import akka.stream.Inlet
import akka.stream.KillSwitch
import akka.stream.KillSwitches
import akka.stream.SinkShape
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.RestartSource
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import com.netflix.spectator.api.Registry
import com.typesafe.scalalogging.StrictLogging
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse

import scala.jdk.CollectionConverters._

class S3CopySink(
  val bucket: String,
  val region: String,
  val prefix: String,
  val registry: Registry,
  implicit val system: ActorSystem
) extends GraphStage[SinkShape[File]]
    with StrictLogging {

  private val in = Inlet[File]("S3CopySink.in")
  override val shape = SinkShape(in)

  private implicit val ec = scala.concurrent.ExecutionContext.global
  private implicit val mat = ActorMaterializer()

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) with InHandler {

      private var s3Client: S3AsyncClient = _
      // This map tracks ongoing file copy flows. It should be thread safe since "remove" happens
      // in a different flow.
      private val activeFiles = new ConcurrentHashMap[String, KillSwitch].asScala
      private val numActiveFiles = registry.distributionSummary("persistence.s3.numActiveFiles")

      override def preStart(): Unit = {
        initS3Client()
        pull(in)
      }

      override def onPush(): Unit = {
        val file = grab(in)
        if (shouldCopy(file)) {
          process(file)
        }
        pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        super.completeStage()
        activeFiles.values.foreach(_.shutdown())
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        super.failStage(ex)
        activeFiles.values.foreach(_.shutdown())
      }

      setHandler(in, this)

      private def initS3Client(): Unit = {
        s3Client = S3AsyncClient
          .builder()
          .region(Region.of(region))
          .build()
      }

      // TODO handle .tmp after long idle?
      private def shouldCopy(f: File): Boolean = {
        !f.getName.endsWith(RollingFileWriter.TmpFileSuffix)
      }

      // Start a new stream to copy each file
      private def process(file: File): Unit = {
        import scala.concurrent.duration._
        import scala.jdk.FutureConverters._

        val killSwitch = RestartSource
          .onFailuresWithBackoff(
            minBackoff = 100.millis,
            maxBackoff = 1.seconds,
            randomFactor = 0,
            maxRestarts = -1
          ) { () =>
            Source.fromFuture(copyToS3Async(file).asScala)
          }
          .viaMat(KillSwitches.single)(Keep.right)
          .toMat(Sink.foreach(_ => cleanupFile(file)))(Keep.left)
          .run

        activeFiles.put(file.getName, killSwitch)
        numActiveFiles.record(activeFiles.size)
      }

      def cleanupFile(file: File): Unit = {
        FileUtil.delete(file.toPath, logger)
        // Un-register from activeFiles map
        activeFiles -= file.getName
      }

      private def copyToS3Async(file: File): CompletableFuture[PutObjectResponse] = {
        val s3Key = buildS3Key(file.getName)
        val putReq = PutObjectRequest
          .builder()
          .bucket(bucket)
          .key(s3Key)
          .build()

        logger.debug(s"copyToS3 start: file=$file, key=$s3Key")
        s3Client.putObject(putReq, file.toPath).whenComplete { (res, err) =>
          {
            if (res != null) {
              logger.info(s"copyToS3 done: file=$file, key=$s3Key")
            } else {
              logger.error(s"copyToS3 failed: file=$file, key=$s3Key, error=${err.getMessage}")
            }
          }
        }
      }

      // Example file name: 2020051003.i-localhost.1.XkvU3A
      private def buildS3Key(fileName: String): String = {
        val hour = fileName.substring(0, HourlyRollingWriter.HourStringLen)
        val s3FileName = fileName.substring(HourlyRollingWriter.HourStringLen + 1)
        val hourPath = hash(s"$prefix/$hour")
        s"$hourPath/$s3FileName"
      }

      private def hash(path: String): String = {
        val md = MessageDigest.getInstance("MD5")
        md.update(path.getBytes("UTF-8"))
        val digest = md.digest()
        val hexBytes = digest.take(2).map("%02x".format(_)).mkString
        val ramdonPrefix = hexBytes.take(3)
        s"$ramdonPrefix/$path"
      }
    }
  }
}
