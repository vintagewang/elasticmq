package org.elasticmq.persistence.sql

import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import org.elasticmq.ElasticMQError
import org.elasticmq.actor.queue._
import org.elasticmq.actor.reply._
import org.elasticmq.msg.{CreateQueue, RestoreMessages}
import org.elasticmq.persistence.{CreateQueueMetadata, QueueConfigUtil}
import org.elasticmq.util.Logging

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class GetAllMessages(queueName: String) extends Replyable[List[InternalMessage]]

class SqlQueuePersistenceActor(messagePersistenceConfig: SqlQueuePersistenceConfig, baseQueues: List[CreateQueueMetadata]) extends Actor with Logging {

  private val queueRepo: QueueRepository = new QueueRepository(messagePersistenceConfig)
  private val repos: mutable.Map[String, MessageRepository] = mutable.HashMap[String, MessageRepository]()

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val ec: ExecutionContext = context.dispatcher

  def receive: Receive = {
    case QueueCreated(queueData) =>
      logger.whenDebugEnabled {
        logger.debug(s"Storing queue data: $queueData")
      }

      if (repos.contains(queueData.name)) {
        queueRepo.update(CreateQueueMetadata.from(queueData))
      } else {
        queueRepo.add(CreateQueueMetadata.from(queueData))
        repos.put(queueData.name, new MessageRepository(queueData.name, messagePersistenceConfig))
      }

    case QueueDeleted(queueName) =>
      logger.whenDebugEnabled {
        logger.debug(s"Removing queue data for queue $queueName")
      }
      queueRepo.remove(queueName)
      repos.remove(queueName).foreach(_.drop())

    case QueueMetadataUpdated(queueData) =>
      logger.whenDebugEnabled {
        logger.debug(s"Updating queue: $queueData")
      }
      queueRepo.update(CreateQueueMetadata.from(queueData))

    case QueueMessageAdded(queueName, message) =>
      logger.whenDebugEnabled {
        logger.debug(s"Adding new message: $message")
      }
      repos.get(queueName).foreach(_.add(message))
      sender() ! OperationSuccessful

    case QueueMessageUpdated(queueName, message) =>
      logger.whenDebugEnabled {
        logger.debug(s"Updating message: $message")
      }
      repos.get(queueName).foreach(_.update(message))
      sender() ! OperationSuccessful

    case QueueMessageRemoved(queueName, messageId) =>
      logger.whenDebugEnabled {
        logger.debug(s"Removing message with id $messageId")
      }
      repos.get(queueName).foreach(_.remove(messageId))
      sender() ! OperationSuccessful

    case Restore(queueManagerActor: ActorRef) =>
      val recip = sender()
      createQueues(queueManagerActor).onComplete {
        case Success(result) => recip ! result
        case Failure(exception) => logger.error("Failed to restore persisted queues", exception)
      }

    case GetAllMessages(queueName) =>
      repos.get(queueName).foreach(repo => sender() ! repo.findAll())
  }

  private def createQueues(queueManagerActor: ActorRef)(implicit timeout: Timeout): Future[Either[List[ElasticMQError], OperationStatus]] = {
    val persistedQueues = queueRepo.findAll()
    val allQueues = QueueConfigUtil.getQueuesToCreate(persistedQueues, baseQueues)

    val restoreResult: List[Future[Either[ElasticMQError, OperationStatus]]] = allQueues.map { cq =>
      restoreQueue(queueManagerActor, cq)
        .flatMap {
            case Left(errors)  => Future.successful(Left(errors))
            case Right(queueActor) => restoreMessages(cq.name, queueActor).map(_ => Right(OperationSuccessful))
          }
    }

    Future.sequence(restoreResult).map(results => {
      val errors = results.flatMap(_.swap.toOption)
      if (errors.nonEmpty) Left(errors) else Right(OperationSuccessful)
    })
  }

  private def restoreQueue(queueManagerActor: ActorRef, cq: CreateQueueMetadata): Future[Either[ElasticMQError, ActorRef]] = {
    queueManagerActor ? CreateQueue(cq.toQueueData)
  }

  private def restoreMessages(queueName: String, queueActor: ActorRef): Future[Unit] = {
    val repository = new MessageRepository(queueName, messagePersistenceConfig)
    repos.put(queueName, repository)
    queueActor ? RestoreMessages(repository.findAll())
  }
}
