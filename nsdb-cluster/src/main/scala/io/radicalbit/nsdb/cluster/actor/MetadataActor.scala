package io.radicalbit.nsdb.cluster.actor

import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.pubsub.DistributedPubSubMediator.{Subscribe, SubscribeAck}
import io.radicalbit.nsdb.cluster.actor.MetadataCoordinator.commands._
import io.radicalbit.nsdb.cluster.actor.MetadataCoordinator.events._
import io.radicalbit.nsdb.cluster.extension.RemoteAddress
import io.radicalbit.nsdb.cluster.index.MetadataIndex
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.store.NIOFSDirectory

import scala.collection.mutable

class MetadataActor(val basePath: String, val coordinator: ActorRef) extends Actor with ActorLogging {

  lazy val metadataIndexes: mutable.Map[String, MetadataIndex] = mutable.Map.empty

  val remoteAddress = RemoteAddress(context.system)

  private def getIndex(namespace: String): MetadataIndex =
    metadataIndexes.getOrElse(
      namespace, {
        val newIndex = new MetadataIndex(new NIOFSDirectory(Paths.get(basePath, namespace, "metadata")))
        metadataIndexes += (namespace -> newIndex)
        newIndex
      }
    )

  override def preStart(): Unit = {
    log.debug("metadata actor started at {}/{}", remoteAddress.address, self.path.name)
  }

  override def receive: Receive = {

    case GetLocations(namespace, metric, occurredOn) =>
      val metadata = getIndex(namespace).getMetadata(metric)
      sender ! LocationsGot(namespace, metric, metadata, occurredOn)

    //FIXME see if this has to be removed
//    case GetLocation(namespace, metric, t, occurredOn) =>
//      val metadata = getIndex(namespace).getMetadata(metric, t)
//      sender ! LocationGot(namespace, metric, t, metadata, occurredOn)

    case AddLocation(namespace, metadata, occurredOn) =>
      val index                        = getIndex(namespace)
      implicit val writer: IndexWriter = index.getWriter
      index.write(metadata)
      writer.close()
      sender ! LocationAdded(namespace, metadata, occurredOn)

    case AddLocations(namespace, metadataSeq, occurredOn) =>
      val index                        = getIndex(namespace)
      implicit val writer: IndexWriter = index.getWriter
      metadataSeq.foreach(index.write)
      writer.close()
      sender ! LocationsAdded(namespace, metadataSeq, occurredOn)

    case UpdateLocation(namespace, oldMetadata, newOccupation, occurredOn) =>
      val index                        = getIndex(namespace)
      implicit val writer: IndexWriter = index.getWriter
      index.delete(oldMetadata)
      index.write(oldMetadata.copy(occupied = newOccupation))
      writer.close()
      sender ! LocationUpdated(namespace, oldMetadata, newOccupation, occurredOn)

    case DeleteLocation(namespace, metadata, occurredOn) =>
      val index                        = getIndex(namespace)
      implicit val writer: IndexWriter = index.getWriter
      index.delete(metadata)
      writer.close()
      sender ! LocationDeleted(namespace, metadata, occurredOn)

    case DeleteNamespace(namespace, occurredOn) =>
      val index                        = getIndex(namespace)
      implicit val writer: IndexWriter = index.getWriter
      index.deleteAll()
      writer.close()
      sender ! NamespaceDeleted(namespace, occurredOn)

    case SubscribeAck(Subscribe("metadata", None, _)) =>
      log.debug("subscribed to topic metadata")
  }
}

object MetadataActor {
  def props(basePath: String, coordinator: ActorRef): Props =
    Props(new MetadataActor(basePath, coordinator))
}