/*
 * Copyright 2018 Radicalbit S.r.l.
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

package io.radicalbit.nsdb.cluster.endpoint

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import io.radicalbit.nsdb.client.rpc.GRPCServer
import io.radicalbit.nsdb.client.rpc.converter.GrpcBitConverters._
import io.radicalbit.nsdb.cluster.coordinator.MetadataCoordinator.commands.PutMetricInfo
import io.radicalbit.nsdb.cluster.coordinator.MetadataCoordinator.events.{MetricInfoFailed, MetricInfoPut}
import io.radicalbit.nsdb.cluster.coordinator.WriteCoordinator.{CreateDump, DumpCreated, Restore, Restored}
import io.radicalbit.nsdb.cluster.index.MetricInfo
import io.radicalbit.nsdb.common.JSerializable
import io.radicalbit.nsdb.common.exception.InvalidStatementException
import io.radicalbit.nsdb.common.protocol._
import io.radicalbit.nsdb.common.statement._
import io.radicalbit.nsdb.model.Schema
import io.radicalbit.nsdb.protocol.MessageProtocol.Commands._
import io.radicalbit.nsdb.protocol.MessageProtocol.Events._
import io.radicalbit.nsdb.rpc.common.{Dimension, Tag}
import io.radicalbit.nsdb.rpc.dump.DumpGrpc.Dump
import io.radicalbit.nsdb.rpc.dump._
import io.radicalbit.nsdb.rpc.health.HealthCheckResponse.ServingStatus
import io.radicalbit.nsdb.rpc.health.HealthGrpc.Health
import io.radicalbit.nsdb.rpc.health.{HealthCheckRequest, HealthCheckResponse}
import io.radicalbit.nsdb.rpc.init.InitMetricGrpc.InitMetric
import io.radicalbit.nsdb.rpc.init.{InitMetricRequest, InitMetricResponse}
import io.radicalbit.nsdb.rpc.request.RPCInsert
import io.radicalbit.nsdb.rpc.requestCommand.{DescribeMetric, ShowMetrics, ShowNamespaces}
import io.radicalbit.nsdb.rpc.requestSQL.SQLRequestStatement
import io.radicalbit.nsdb.rpc.response.RPCInsertResult
import io.radicalbit.nsdb.rpc.responseCommand.MetricSchemaRetrieved.MetricField.{FieldClassType => GrpcFieldClassType}
import io.radicalbit.nsdb.rpc.responseCommand.{
  Namespaces,
  MetricSchemaRetrieved => GrpcMetricSchemaRetrieved,
  MetricsGot => GrpcMetricsGot
}
import io.radicalbit.nsdb.rpc.responseSQL.SQLStatementResponse
import io.radicalbit.nsdb.rpc.service.NSDBServiceCommandGrpc.NSDBServiceCommand
import io.radicalbit.nsdb.rpc.service.NSDBServiceSQLGrpc.NSDBServiceSQL
import io.radicalbit.nsdb.sql.parser.SQLStatementParser
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/**
  * Concrete implementation of Nsdb's Grpc endpoint
  * @param readCoordinator the read coordinator actor
  * @param writeCoordinator the write coordinator actor
  * @param system the global actor system
  */
class GrpcEndpoint(readCoordinator: ActorRef, writeCoordinator: ActorRef, metadataCoordinator: ActorRef)(
    implicit system: ActorSystem)
    extends GRPCServer {

  private val log = LoggerFactory.getLogger(classOf[GrpcEndpoint])

  implicit val timeout: Timeout =
    Timeout(system.settings.config.getDuration("nsdb.rpc-endpoint.timeout", TimeUnit.SECONDS), TimeUnit.SECONDS)
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher

  override protected[this] val executionContextExecutor: ExecutionContext = implicitly[ExecutionContext]

  override protected[this] def serviceSQL: NSDBServiceSQL = GrpcEndpointServiceSQL

  override protected[this] def serviceCommand: NSDBServiceCommand = GrpcEndpointServiceCommand

  override protected[this] def initMetricService: InitMetric = InitMetricService

  override protected[this] def health: Health = GrpcEndpointServiceHealth

  override protected[this] def dump: Dump = GrpcEndpointServiceDump

  override protected[this] val port: Int = system.settings.config.getInt("nsdb.grpc.port")

  override protected[this] val parserSQL = new SQLStatementParser

  start() match {
    case Success(_) =>
      log.info("GrpcEndpoint started on port {}", port)
      system.registerOnTermination {
        stop()
      }
    case Failure(ex) => log.error("error in starting Grpc endpoint", ex)
  }

  /**
    * Concrete implementation of the health check Grpc service
    */
  protected[this] object GrpcEndpointServiceHealth extends Health {
    override def check(request: HealthCheckRequest): Future[HealthCheckResponse] =
      Future.successful(HealthCheckResponse(ServingStatus.SERVING))
  }

  protected[this] object GrpcEndpointServiceDump extends Dump {
    override def createDump(request: DumpRequest): Future[DumpResponse] = {
      log.debug(s"Sending to WriteCoordinator dump request for: ${request.targets.mkString(",")}")
      (writeCoordinator ? CreateDump(request.destPath, request.targets)).map {
        case DumpCreated(inputPath) => DumpResponse(startedSuccessfully = true, dumpPath = inputPath)
        case msg =>
          log.error("got {} from dump request", msg)
          DumpResponse(startedSuccessfully = false, errorMsg = "unknown response from write coordinator")
      }
    }

    override def restore(request: RestoreRequest): Future[RestoreResponse] = {
      (writeCoordinator ? Restore(request.sourcePath)).map {
        case Restored(path) => RestoreResponse(startedSuccessfully = true, path)
        case msg =>
          log.error("got {} from restore request", msg)
          RestoreResponse(startedSuccessfully = false,
                          errorMsg = "unknown response from write coordinator",
                          path = request.sourcePath)
      }
    }
  }

  /**
    * Concrete implementation of the init metric service.
    */
  protected[this] object InitMetricService extends InitMetric {
    override def initMetric(request: InitMetricRequest): Future[InitMetricResponse] = {

      Try { Duration(request.shardInterval).toMillis } match {
        case Success(interval) =>
          (metadataCoordinator ? PutMetricInfo(request.db, request.namespace, MetricInfo(request.metric, interval)))
            .map {
              case MetricInfoPut(_, _, _) =>
                InitMetricResponse(request.db, request.namespace, request.metric, completedSuccessfully = true)
              case MetricInfoFailed(_, _, _, message) =>
                InitMetricResponse(request.db,
                                   request.namespace,
                                   request.metric,
                                   completedSuccessfully = false,
                                   errorMsg = message)
              case _ =>
                InitMetricResponse(request.db,
                                   request.namespace,
                                   request.metric,
                                   completedSuccessfully = false,
                                   errorMsg = "Unknown response from server")
            }
        case Failure(ex) =>
          Future(
            InitMetricResponse(request.db,
                               request.namespace,
                               request.metric,
                               completedSuccessfully = false,
                               errorMsg = ex.getMessage))
      }
    }
  }

  /**
    * Concrete implementation of the command Grpc service
    */
  protected[this] object GrpcEndpointServiceCommand extends NSDBServiceCommand {
    override def showMetrics(
        request: ShowMetrics
    ): Future[GrpcMetricsGot] = {
      log.debug("Received command ShowMetrics for namespace {}", request.namespace)
      (readCoordinator ? GetMetrics(request.db, request.namespace)).map {
        case MetricsGot(db, namespace, metrics) =>
          GrpcMetricsGot(db, namespace, metrics.toList, completedSuccessfully = true)
        case _ =>
          GrpcMetricsGot(request.db,
                         request.namespace,
                         List.empty,
                         completedSuccessfully = false,
                         "Unknown server error")
      }
    }

    override def describeMetric(request: DescribeMetric): Future[GrpcMetricSchemaRetrieved] = {

      def extractField(schema: Option[Schema]): Set[MetricField] =
        schema
          .map(
            _.fields.map(
              field =>
                MetricField(name = field.name,
                            fieldClassType = field.fieldClassType,
                            `type` = field.indexType.getClass.getSimpleName)))
          .getOrElse(Set.empty[MetricField])

      def extractFieldClassType(f: MetricField): GrpcFieldClassType = {
        f.fieldClassType match {
          case DimensionFieldType => GrpcFieldClassType.DIMENSION
          case TagFieldType       => GrpcFieldClassType.TAG
          case TimestampFieldType => GrpcFieldClassType.TIMESTAMP
          case ValueFieldType     => GrpcFieldClassType.VALUE
        }
      }

      //TODO: add failure handling
      log.debug("Received command DescribeMetric for metric {}", request.metric)

      (readCoordinator ? GetSchema(db = request.db, namespace = request.namespace, metric = request.metric))
        .map {
          case SchemaGot(db, namespace, metric, schema) =>
            GrpcMetricSchemaRetrieved(
              db,
              namespace,
              metric,
              extractField(schema)
                .map(f => GrpcMetricSchemaRetrieved.MetricField(f.name, extractFieldClassType(f), f.`type`))
                .toSeq,
              completedSuccessfully = true
            )
          case _ =>
            GrpcMetricSchemaRetrieved(request.db,
                                      request.namespace,
                                      request.metric,
                                      completedSuccessfully = false,
                                      errors = "unknown message received from server")
        }
    }

    override def showNamespaces(
        request: ShowNamespaces
    ): Future[Namespaces] = {
      log.debug("Received command ShowNamespaces for db: {}", request.db)
      (readCoordinator ? GetNamespaces(db = request.db))
        .map {
          case NamespacesGot(db, namespaces) =>
            Namespaces(db = db, namespaces = namespaces.toSeq, completedSuccessfully = true)
          case _ =>
            Namespaces(db = request.db, completedSuccessfully = false, errors = "Cluster unable to fulfill request")
        }
        .recoverWith {
          case _ =>
            Future.successful(
              Namespaces(db = request.db, completedSuccessfully = false, errors = "Cluster unable to fulfill request"))
        }
    }
  }

  /**
    * Concrete implementation of the Sql Grpc service
    */
  protected[this] object GrpcEndpointServiceSQL extends NSDBServiceSQL {

    override def insertBit(request: RPCInsert): Future[RPCInsertResult] = {
      log.debug("Received a write request {}", request)

      val bit = Bit(
        timestamp = request.timestamp,
        dimensions = request.dimensions.collect {
          case (k, v) if !v.value.isStringValue || v.getStringValue != "" => (k, dimensionFor(v.value))
        },
        tags = request.tags.collect {
          case (k, v) if !v.value.isStringValue || v.getStringValue != "" => (k, tagFor(v.value))
        },
        value = valueFor(request.value)
      )

      val res = (writeCoordinator ? MapInput(
        db = request.database,
        namespace = request.namespace,
        metric = request.metric,
        ts = request.timestamp,
        record = bit
      )).map {
        case _: InputMapped =>
          RPCInsertResult(completedSuccessfully = true)
        case msg: RecordRejected =>
          RPCInsertResult(completedSuccessfully = false, errors = msg.reasons.mkString(","))
        case _ => RPCInsertResult(completedSuccessfully = false, errors = "unknown reason")
      } recover {
        case t =>
          log.error(s"error while inserting $bit", t)
          RPCInsertResult(completedSuccessfully = false, t.getMessage)
      }

      res.onComplete {
        case Success(res: RPCInsertResult) =>
          log.debug("Completed the write request {}", request)
          if (res.completedSuccessfully)
            log.debug("The result is {}", res)
          else
            log.error("The result is {}", res)
        case Failure(t: Throwable) =>
          log.error(s"error on request $request", t)
      }
      res
    }

    private def valueFor(v: RPCInsert.Value): JSerializable = v match {
      case _: RPCInsert.Value.DecimalValue => v.decimalValue.get
      case _: RPCInsert.Value.LongValue    => v.longValue.get
    }

    private def dimensionFor(v: Dimension.Value): JSerializable = v match {
      case _: Dimension.Value.DecimalValue => v.decimalValue.get
      case _: Dimension.Value.LongValue    => v.longValue.get
      case _                               => v.stringValue.get
    }

    private def tagFor(v: Tag.Value): JSerializable = v match {
      case _: Tag.Value.DecimalValue => v.decimalValue.get
      case _: Tag.Value.LongValue    => v.longValue.get
      case _                         => v.stringValue.get
    }

    override def executeSQLStatement(
        request: SQLRequestStatement
    ): Future[SQLStatementResponse] = {
      val requestDb        = request.db
      val requestNamespace = request.namespace
      val sqlStatement     = parserSQL.parse(request.db, request.namespace, request.statement)
      sqlStatement match {
        // Parsing Success
        case Success(statement) =>
          statement match {
            case select: SelectSQLStatement =>
              log.debug("Received a select request {}", select)
              (readCoordinator ? ExecuteStatement(select))
                .map {
                  // SelectExecution Success
                  case SelectStatementExecuted(db, namespace, metric, values: Seq[Bit]) =>
                    log.debug("SQL statement succeeded on db {} with namespace {} and metric {}",
                              db,
                              namespace,
                              metric)
                    SQLStatementResponse(
                      db = db,
                      namespace = namespace,
                      metric = metric,
                      completedSuccessfully = true,
                      records = values.map(bit => bit.asGrpcBit)
                    )
                  // SelectExecution Failure
                  case SelectStatementFailed(reason, _) =>
                    SQLStatementResponse(
                      db = requestDb,
                      namespace = requestNamespace,
                      completedSuccessfully = false,
                      reason = reason
                    )
                }
                .recoverWith {
                  case t =>
                    log.error("", t)
                    Future.successful(
                      SQLStatementResponse(
                        db = requestDb,
                        namespace = requestNamespace,
                        completedSuccessfully = false,
                        reason = t.getMessage
                      ))
                }
            case insert: InsertSQLStatement =>
              log.debug("Received a insert request {}", insert)
              val result = InsertSQLStatement
                .unapply(insert)
                .map {
                  case (db, namespace, metric, ts, dimensions, tags, value) =>
                    val timestamp = ts getOrElse System.currentTimeMillis
                    writeCoordinator ? MapInput(
                      timestamp,
                      db,
                      namespace,
                      metric,
                      Bit(timestamp = timestamp,
                          value = value,
                          dimensions = dimensions.map(_.fields).getOrElse(Map.empty),
                          tags = tags.map(_.fields).getOrElse(Map.empty))
                    )
                }
                .getOrElse(Future(throw new InvalidStatementException("The insert SQL statement is invalid.")))

              result
                .map {
                  case InputMapped(db, namespace, metric, record) =>
                    SQLStatementResponse(db = db,
                                         namespace = namespace,
                                         metric = metric,
                                         completedSuccessfully = true,
                                         records = Seq(record.asGrpcBit))
                  case msg: RecordRejected =>
                    SQLStatementResponse(db = msg.db,
                                         namespace = msg.namespace,
                                         metric = msg.metric,
                                         completedSuccessfully = false,
                                         reason = msg.reasons.mkString(","))
                  case _ =>
                    SQLStatementResponse(db = insert.db,
                                         namespace = insert.namespace,
                                         metric = insert.metric,
                                         completedSuccessfully = false,
                                         reason = "unknown reason")
                }

            case delete: DeleteSQLStatement =>
              (writeCoordinator ? ExecuteDeleteStatement(delete))
                .mapTo[DeleteStatementExecuted]
                .map(
                  x =>
                    SQLStatementResponse(db = x.db,
                                         namespace = x.namespace,
                                         metric = x.metric,
                                         completedSuccessfully = true,
                                         records = Seq.empty))
                .recoverWith {
                  case t =>
                    Future.successful(
                      SQLStatementResponse(
                        db = requestDb,
                        namespace = requestNamespace,
                        completedSuccessfully = false,
                        reason = t.getMessage
                      ))
                }

            case _: DropSQLStatement =>
              (writeCoordinator ? DropMetric(statement.db, statement.namespace, statement.metric))
                .mapTo[MetricDropped]
                .map(
                  x =>
                    SQLStatementResponse(db = x.db,
                                         namespace = x.namespace,
                                         metric = x.metric,
                                         completedSuccessfully = true,
                                         records = Seq.empty))
                .recoverWith {
                  case t =>
                    Future.successful(
                      SQLStatementResponse(
                        db = requestDb,
                        namespace = requestNamespace,
                        completedSuccessfully = false,
                        reason = t.getMessage
                      ))
                }
          }

        //Parsing Failure
        case Failure(exception: InvalidStatementException) =>
          Future.successful(
            SQLStatementResponse(db = request.db,
                                 namespace = request.namespace,
                                 completedSuccessfully = false,
                                 reason = "sql statement not valid",
                                 message = exception.message)
          )
        case Failure(ex) =>
          Future.successful(
            SQLStatementResponse(db = request.db,
                                 namespace = request.namespace,
                                 completedSuccessfully = false,
                                 reason = "internal error",
                                 message = ex.getMessage)
          )
      }
    }
  }
}
