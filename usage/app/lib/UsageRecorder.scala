package lib

import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import play.api.libs.json._

import rx.lang.scala.{Observable, Subscriber}

import model._


object UsageRecorder {
  val usageStream = UsageStream.observable
  val windowDuration = 1.second

  val observable = usageStream.flatMap(recordUpdates).retry((_, error) => {
    Logger.error("UsageRecorder encountered an error.", error)
    UsageMetrics.incrementErrors

    true
  })

  val subscriber = Subscriber((usage: JsObject) => {
      Logger.debug(s"UsageRecorder processed update: ${usage}")
      UsageMetrics.incrementUpdated
  })

  val tumblingObservable = observable.tumbling(windowDuration)
  lazy val subscription = tumblingObservable.flatten.subscribe(subscriber)

  def subscribe = subscription
  def unsubscribe = subscription.unsubscribe

  def recordUpdates(usageGroup: UsageGroup) = {
    UsageTable.matchUsageGroup(usageGroup).flatMap(dbUsageGroup => {

      val deletes = (dbUsageGroup.usages -- usageGroup.usages).map(UsageTable.delete(_))
      val creates = (usageGroup.usages -- dbUsageGroup.usages).map(UsageTable.create(_))
      val updates = (usageGroup.usages & dbUsageGroup.usages).map(UsageTable.update(_))

      Observable.from(deletes ++ updates ++ creates).flatten[JsObject]
    })
  }
}
