package org.elasticmq.actor.queue

import akka.actor.{ActorContext, ActorRef}
import akka.util.Timeout
import org.elasticmq.actor.reply._
import org.elasticmq.util.NowProvider
import org.elasticmq.{FifoDeduplicationIdsHistory, QueueData}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

trait QueueActorStorage {
  def nowProvider: NowProvider

  def initialQueueData: QueueData

  var deadLettersActorRef: Option[ActorRef]
  def copyMessagesToActorRef: Option[ActorRef]
  def moveMessagesToActorRef: Option[ActorRef]
  def queueEventListener: Option[ActorRef]

  def context: ActorContext

  implicit lazy val ec: ExecutionContext = context.dispatcher
  implicit lazy val timeout: Timeout = 5.seconds

  var queueData: QueueData = initialQueueData
  var messageQueue: MessageQueue = MessageQueue(queueData.isFifo)
  var fifoMessagesHistory: FifoDeduplicationIdsHistory = FifoDeduplicationIdsHistory.newHistory()
  val receiveRequestAttemptCache = new ReceiveRequestAttemptCache

  def sendMessageAddedNotification(internalMessage: InternalMessage): Future[OperationStatus] = {
    queueEventListener
      .map { ref =>
        ref ? QueueMessageAdded(queueData.name, internalMessage)
      }
      .getOrElse(Future.successful(OperationUnsupported))
  }

  def sendMessageUpdatedNotification(internalMessage: InternalMessage): Future[OperationStatus] = {
    queueEventListener
      .map { ref =>
        ref ? QueueMessageUpdated(queueData.name, internalMessage)
      }
      .getOrElse(Future.successful(OperationUnsupported))
  }

  def sendMessageRemovedNotification(msgId: String): Future[OperationStatus] = {
    queueEventListener
      .map { ref =>
        ref ? QueueMessageRemoved(queueData.name, msgId)
      }
      .getOrElse(Future.successful(OperationUnsupported))
  }
}
