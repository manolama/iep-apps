/*
 * Copyright 2014-2021 Netflix, Inc.
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
package com.netflix.atlas.druid

import java.time.Instant
import akka.NotUsed
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.netflix.atlas.akka.AccessLogger
import com.netflix.atlas.core.index.TagQuery
import com.netflix.atlas.core.model.ArrayTimeSeq
import com.netflix.atlas.core.model.ConsolidationFunction
import com.netflix.atlas.core.model.DataExpr
import com.netflix.atlas.core.model.DefaultSettings
import com.netflix.atlas.core.model.DsType
import com.netflix.atlas.core.model.EvalContext
import com.netflix.atlas.core.model.Query
import com.netflix.atlas.core.model.Query.KeyQuery
import com.netflix.atlas.core.model.Query.KeyValueQuery
import com.netflix.atlas.core.model.Query.PatternQuery
import com.netflix.atlas.core.model.Tag
import com.netflix.atlas.core.model.TimeSeries
import com.netflix.atlas.core.util.ArrayHelper
import com.netflix.atlas.core.util.ListHelper
import com.netflix.atlas.json.Json
import com.netflix.spectator.impl.PatternMatcher
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

class DruidDatabaseActor(config: Config) extends Actor with StrictLogging {

  import DruidClient._
  import DruidDatabaseActor._

  import scala.concurrent.ExecutionContext.Implicits.global

  import ExplainApi._
  import com.netflix.atlas.webapi.GraphApi._
  import com.netflix.atlas.webapi.TagsApi._

  private implicit val sys: ActorSystem = context.system

  private val client =
    new DruidClient(config.getConfig("atlas.druid"), sys, Http().superPool[AccessLogger]())

  private val maxDataSize = config.getBytes("atlas.druid.max-data-size")

  private val tagsInterval = config.getDuration("atlas.druid.tags-interval")

  private val datasourceFilter =
    PatternMatcher.compile(config.getString("atlas.druid.datasource-filter"))

  private val normalizeRates = config.getBoolean("atlas.druid.normalize-rates")

  private var metadata: Metadata = Metadata(Nil)

  private val cancellable =
    context.system.scheduler.scheduleAtFixedRate(0.seconds, 10.minutes, self, Tick)

  def receive: Receive = {
    case Tick        => refreshMetadata(sender())
    case m: Metadata => metadata = Metadata(m.datasources.filter(_.nonEmpty))

    case ListTagsRequest(tq)   => listValues(sendTags(sender()), tq)
    case ListKeysRequest(tq)   => listKeys(sender(), tq)
    case ListValuesRequest(tq) => listValues(sendValues(sender()), tq)
    case req: DataRequest      => fetchData(sender(), req)
    case req: ExplainRequest   => explain(sender(), req.dataRequest)
  }

  private def refreshMetadata(ref: ActorRef): Unit = {
    client.datasources
      .flatMapConcat { ds =>
        val sources = ds
          .filter(datasourceFilter.matches)
          .map { d =>
            client
              .segmentMetadata(SegmentMetadataQuery(d, List(tagsQueryInterval)))
              .filter(_.nonEmpty)
              .map { rs =>
                DatasourceMetadata(d, rs.head.toDatasource)
              }
          }
        Source(sources)
      }
      .flatMapConcat(v => v)
      .fold(List.empty[DatasourceMetadata]) { (acc, v) =>
        v :: acc
      }
      .map { vs =>
        logger.trace(s"metadata: ${Json.encode(vs)}")
        Metadata(vs)
      }
      .runWith(Sink.head)
      .onComplete {
        case Success(m) => ref ! m
        case Failure(t) => logger.warn("failed to refresh metadata", t)
      }
  }

  private def listKeys(ref: ActorRef, tq: TagQuery): Unit = {
    val query = tq.query.getOrElse(Query.True)
    val vs = metadata.datasources
      .filter { ds =>
        ds.metrics.exists(query.couldMatch)
      }
      .flatMap { ds =>
        "nf.datasource" :: "name" :: ds.datasource.dimensions
      }
      .distinct
      .sorted
    ref ! KeyListResponse(vs)
  }

  type ListCallback = (String, List[String]) => Unit

  private def sendValues(ref: ActorRef)(k: String, vs: List[String]): Unit = {
    ref ! ValueListResponse(vs)
  }

  private def sendTags(ref: ActorRef)(k: String, vs: List[String]): Unit = {
    ref ! TagListResponse(vs.map(v => Tag(k, v)))
  }

  private def listValues(callback: ListCallback, tq: TagQuery): Unit = {
    tq.key.getOrElse("name") match {
      case "name"          => listNames(callback, tq)
      case "nf.datasource" => listDatasources(callback, tq)
      case _               => listDimension(callback, tq)
    }
  }

  private def getListQuery(tq: TagQuery): Query = {
    val userQuery = tq.query.getOrElse(Query.True)
    if (tq.offset == null || tq.offset == "")
      userQuery
    else
      userQuery.and(Query.GreaterThan(tq.key.getOrElse("name"), tq.offset))
  }

  private def listNames(callback: ListCallback, tq: TagQuery): Unit = {
    val query = getListQuery(tq)
    val vs = metadata.datasources
      .flatMap(_.metrics)
      .filter(query.couldMatch)
      .map(m => m("name"))
      .distinct
      .sorted
      .take(tq.limit)
    callback("name", vs)
  }

  private def listDatasources(callback: ListCallback, tq: TagQuery): Unit = {
    val query = getListQuery(tq)
    val vs = metadata.datasources
      .flatMap(_.metrics)
      .filter(query.couldMatch)
      .map(m => m("nf.datasource"))
      .distinct
      .sorted
      .take(tq.limit)
    callback("nf.datasource", vs)
  }

  private def listDimension(callback: ListCallback, tq: TagQuery): Unit = {
    val query = getListQuery(tq)
    tq.key match {
      case Some(k) =>
        val datasources = metadata.datasources.filter { ds =>
          ds.datasource.dimensions.contains(k) && ds.metrics.exists(query.couldMatch)
        }

        if (datasources.nonEmpty) {
          val dimensionFilterMatches = Query
            .cnfList(query)
            .map(toInClause)
            .collect {
              case q: KeyValueQuery if q.k == k => q
              case q: PatternQuery if q.k == k  => q
            }
          val topnQuery = TopNQuery(
            dataSources = datasources.map { ds =>
              ds.name
            },
            dimension = toDimensionSpec(DefaultDimensionSpec(k, "value"), dimensionFilterMatches),
            intervals = List(tagsQueryInterval),
            filter = DruidFilter.forQuery(query),
            threshold = if (tq.limit < Int.MaxValue) tq.limit else 1000
          )
          val druidQueries = List(client.topn(topnQuery))
          Source(druidQueries)
            .flatMapMerge(Int.MaxValue, v => v)
            .map(_.flatMap(_.values))
            .fold(List.empty[String]) { (vs1, vs2) =>
              ListHelper.merge(tq.limit, vs1, vs2)
            }
            .runWith(Sink.foreach { vs =>
              callback(k, vs)
            })
        } else {
          callback(null, Nil)
        }
      case None =>
        callback(null, Nil)
    }
  }

  private def tagsQueryInterval: String = {
    val now = Instant.now()
    s"${now.minus(tagsInterval)}/$now"
  }

  private def explain(ref: ActorRef, request: DataRequest): Unit = {
    val context = request.context
    val explanation = request.exprs
      .flatMap { expr =>
        val offset = expr.offset.toMillis
        val fetchContext =
          if (offset == 0L) context
          else {
            context.copy(context.start - offset, context.end - offset)
          }
        val exprStr = expr.toString()
        toDruidQueries(metadata, fetchContext, expr)
          .map {
            case (tags, q) => Map("data-expr" -> exprStr, "tags" -> tags, "druid-query" -> q)
          }
      }
      .sortWith { (m1, m2) =>
        m1("data-expr").asInstanceOf[String] < m2("data-expr").asInstanceOf[String]
      }
    ref ! explanation
  }

  private def fetchData(ref: ActorRef, request: DataRequest): Unit = {
    val druidQueries = request.exprs.map { expr =>
      fetchData(request.context, expr).map(ts => expr -> ts)
    }
    Source(druidQueries)
      .flatMapMerge(Int.MaxValue, v => v)
      .fold(Map.empty[DataExpr, List[TimeSeries]]) { (acc, t) =>
        acc + t
      }
      .runWith(Sink.head)
      .onComplete {
        case Success(data) => ref ! DataResponse(data)
        case Failure(t)    => ref ! Failure(t)
      }
  }

  private def fetchData(context: EvalContext, expr: DataExpr): Source[List[TimeSeries], NotUsed] = {
    val offset = expr.offset.toMillis
    val fetchContext =
      if (offset == 0L) context
      else {
        context.copy(context.start - offset, context.end - offset)
      }
    val query = expr.query

    val valueMapper = createValueMapper(normalizeRates, fetchContext, expr)

    val druidQueries = toDruidQueries(metadata, fetchContext, expr).map {
      case (tags, groupByQuery) =>
        client.data(groupByQuery).map { result =>
          val candidates = toTimeSeries(tags, fetchContext, result, maxDataSize, valueMapper)
          // See behavior on multi-value dimensions:
          // http://druid.io/docs/latest/querying/groupbyquery.html
          //
          // The queries sent to druid will include a filtering dimension spec to do as
          // much of the reduction server side as possible. However, it only supports list
          // and regex filters. Some queries, e.g., greater than, cannot be done with the
          // server side filter so we filter candidates locally to ensure the correct
          // results are included.
          val series = candidates.filter(ts => query.couldMatch(ts.tags))
          if (offset == 0L) series else series.map(_.offset(offset))
        }
    }

    // Filtering should have already been done at this point, just focus on the aggregation
    // behavior when evaluating the results. The query is simplified to exact matches that
    // are extracted to generate the labels. At this stage it is effectively true for matches.
    val evalExpr = expr
      .rewrite {
        case _: Query => simplifyExact(expr.query)
      }
      .asInstanceOf[DataExpr]

    Source(druidQueries)
      .flatMapMerge(Int.MaxValue, v => v)
      .fold(List.empty[TimeSeries])(_ ::: _)
      .map { ts =>
        evalExpr.eval(context, ts).data
      }
  }

  override def postStop(): Unit = {
    cancellable.cancel()
    super.postStop()
  }
}

object DruidDatabaseActor {
  import DruidClient._

  case object Tick

  case class Metadata(datasources: List[DatasourceMetadata])

  case class DatasourceMetadata(name: String, datasource: Datasource) {

    val metrics: List[Map[String, String]] = datasource.metrics.map { metric =>
      Map("name" -> metric.name, "nf.datasource" -> name)
    }

    def nonEmpty: Boolean = datasource.metrics.nonEmpty
  }

  def isSpecial(k: String): Boolean = {
    k == "name" || k == "nf.datasource"
  }

  def toDimensionSpec(key: String, query: Query): DimensionSpec = {
    val matches = Query
      .cnfList(query)
      .map(toInClause)
      .collect {
        case q: KeyQuery if q.k == key => q
      }
    toDimensionSpec(DefaultDimensionSpec(key, key), matches)
  }

  private def getKey(query: Query): Option[String] = {
    query match {
      case q: Query.Equal             => Some(q.k)
      case q: Query.In                => Some(q.k)
      case Query.Not(Query.HasKey(k)) => Some(k)
      case _                          => None
    }
  }

  private def getValue(query: Query): List[String] = {
    query match {
      case q: Query.Equal             => List(q.v)
      case q: Query.In                => q.vs
      case Query.Not(Query.HasKey(_)) => List("")
      case _                          => Nil
    }
  }

  def toInClause(query: Query): Query = {
    val qs = Query.dnfList(query)
    val keys = qs.map(getKey).distinct
    keys match {
      case List(Some(k)) => Query.In(k, qs.flatMap(getValue).distinct)
      case _             => query
    }
  }

  @scala.annotation.tailrec
  private def toDimensionSpec(delegate: DimensionSpec, queries: List[Query]): DimensionSpec = {
    queries match {
      case Query.Equal(_, v) :: qs =>
        toDimensionSpec(ListFilteredDimensionSpec(delegate, List(v)), qs)
      case Query.In(_, vs) :: qs =>
        toDimensionSpec(ListFilteredDimensionSpec(delegate, vs), qs)
      case Query.Regex(_, p) :: qs =>
        toDimensionSpec(RegexFilteredDimensionSpec(delegate, s"^$p.*"), qs)
      case Query.RegexIgnoreCase(_, p) :: qs =>
        toDimensionSpec(RegexFilteredDimensionSpec(delegate, s"(?i)^$p.*"), qs)
      case _ :: qs =>
        toDimensionSpec(delegate, qs)
      case Nil =>
        delegate
    }
  }

  @scala.annotation.tailrec
  def toAggregation(name: String, expr: DataExpr): Aggregation = {
    expr match {
      case _: DataExpr.All              => throw new UnsupportedOperationException(":all")
      case DataExpr.GroupBy(e, _)       => toAggregation(name, e)
      case DataExpr.Consolidation(e, _) => toAggregation(name, e)
      case _: DataExpr.Sum              => Aggregation.sum(name)
      case _: DataExpr.Max              => Aggregation.max(name)
      case _: DataExpr.Min              => Aggregation.min(name)
      case _: DataExpr.Count            => Aggregation.count(name)
    }
  }

  def toInterval(context: EvalContext): String = {
    val start = Instant.ofEpochMilli(context.start)
    val end = Instant.ofEpochMilli(context.end)
    s"$start/$end"
  }

  def createValueMapper(
    normalizeRates: Boolean,
    context: EvalContext,
    expr: DataExpr
  ): Double => Double = {

    if (normalizeRates) {
      // Assume all values are counters that are in a rate per step. To make it consistent
      // with Atlas conventions, the value should be reported as a rate per second. This
      // may need to be revisited in the future if other types are supported.
      val stepSeconds = context.step / 1000.0
      (v: Double) => v / stepSeconds
    } else {
      expr.cf match {
        case ConsolidationFunction.Avg =>
          // If consolidating using average, divide by the multiple to preserve the unit
          // for the consolidated value.
          val multiple = context.step / DefaultSettings.stepSize
          (v: Double) => v / multiple
        case _ =>
          // For other consolidation functions like min, max, and sum, the value does not
          // need to be modified.
          (v: Double) => v
      }
    }
  }

  def toTimeSeries(
    commonTags: Map[String, String],
    context: EvalContext,
    data: List[GroupByDatapoint],
    limit: Long,
    valueMapper: Double => Double
  ): List[TimeSeries] = {
    val stepSeconds = context.step / 1000.0
    val arrays = scala.collection.mutable.AnyRefMap.empty[Map[String, String], Array[Double]]
    val length = context.bufferSize
    val bytesPerTimeSeries = 8L * length
    data.foreach { d =>
      val tags = d.tags
      val array = arrays.getOrElseUpdate(tags, ArrayHelper.fill(length, Double.NaN))
      val t = d.timestamp
      val i = ((t - context.start) / context.step).toInt
      if (i >= 0 && i < length) {
        array(i) = valueMapper(d.value)
      }
      // Stop early if data size is too large to avoid GC issues
      if (arrays.size * bytesPerTimeSeries > limit) {
        throw new IllegalStateException(s"data size exceeds $limit bytes")
      }
    }
    arrays.toList.map {
      case (tags, vs) =>
        val seq = new ArrayTimeSeq(DsType.Rate, context.start, context.step, vs)
        TimeSeries(commonTags ++ tags, seq)
    }
  }

  def toDruidQueries(
    metadata: Metadata,
    context: EvalContext,
    expr: DataExpr
  ): List[(Map[String, String], DataQuery)] = {
    val query = expr.query
    val metrics = metadata.datasources.flatMap { ds =>
      ds.metrics.filter(query.couldMatch).map { m =>
        m -> ds.datasource.dimensions
      }
    }
    val intervals = List(toInterval(context))

    metrics.flatMap {
      case (m, ds) =>
        val name = m("name")
        val datasource = m("nf.datasource")

        // Common tags should be extracted for the simplified query rather than the raw
        // query. The simplified query may have additional exact matches due to simplified
        // OR clauses that need to be maintained for correct processing in the eval step.
        val simpleQuery = simplify(query, ds)
        val commonTags = exactTags(simpleQuery)
        val tags = commonTags ++ m

        // Add has key checks for all keys within the group by. This allows druid to remove
        // some of the results earlier on in the historical nodes.
        val finalQuery = expr.finalGrouping
          .filterNot(isSpecial)
          .map(k => Query.HasKey(k))
          .foldLeft(simpleQuery) { (q1, q2) =>
            q1.and(q2)
          }

        if (simpleQuery == Query.False) {
          None
        } else {
          val dimensions = getDimensions(simpleQuery, expr.finalGrouping)

          // Add to the filter to remove rows that don't include the metric we're aggregating.
          // This reduces what needs to be merged and passed back to the broker, improving query performance.
          val metricValueFilter = Query.Not(Query.Equal(name, "0")).and(Query.HasKey(name))
          val finalQueryWithFilter = finalQuery.and(metricValueFilter)

          val groupByQuery = GroupByQuery(
            dataSource = datasource,
            dimensions = dimensions,
            intervals = intervals,
            aggregations = List(toAggregation(name, expr)),
            filter = DruidFilter.forQuery(finalQueryWithFilter),
            granularity = Granularity.millis(context.step)
          )
          val druidQuery =
            if (groupByQuery.dimensions.isEmpty) groupByQuery.toTimeseriesQuery else groupByQuery
          Some(tags -> druidQuery)
        }
    }
  }

  def exactTags(query: Query): Map[String, String] = {
    query match {
      case Query.And(q1, q2) => exactTags(q1) ++ exactTags(q2)
      case Query.Equal(k, v) => Map(k -> v)
      case _                 => Map.empty
    }
  }

  def getDimensions(expr: DataExpr): List[DimensionSpec] = {
    getDimensions(expr.query, expr.finalGrouping)
  }

  def getDimensions(query: Query, groupByKeys: List[String]): List[DimensionSpec] = {
    groupByKeys.filterNot(isSpecial).map(k => toDimensionSpec(k, query))
  }

  /**
    * Simplify the query by mapping clauses for dimensions that are not present in the data source
    * to false.
    */
  private def simplify(query: Query, ds: List[String]): Query = {
    val simpleQuery = query match {
      case Query.And(q1, q2)               => Query.And(simplify(q1, ds), simplify(q2, ds))
      case Query.Or(q1, q2)                => Query.Or(simplify(q1, ds), simplify(q2, ds))
      case Query.Not(q)                    => Query.Not(simplify(q, ds))
      case q: KeyQuery if isSpecial(q.k)   => q
      case q: KeyQuery if ds.contains(q.k) => q
      case _: KeyQuery                     => Query.False
      case q: Query                        => q
    }
    Query.simplify(simpleQuery)
  }

  /**
    * Simplify the query by keeping only clauses that have exact matches.
    */
  private[druid] def simplifyExact(query: Query): Query = {
    val simpleQuery = query match {
      case Query.And(q1, q2) => Query.And(simplifyExact(q1), simplifyExact(q2))
      case q: Query.Equal    => q
      case _: Query          => Query.True
    }
    Query.simplify(simpleQuery)
  }
}
