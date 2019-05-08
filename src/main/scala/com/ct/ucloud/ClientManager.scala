package com.ct.ucloud

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{ActorRef, Scheduler}
import com.ct.ucloud.actor.RunJob

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
case class ClientId(id: Long) extends Serializable {
  override def toString: String = s"[client-$id]"
}
case class Client(
  name: String,
  devices: List[Device],
  var ref: ActorRef = null
) extends Serializable

class ClientManager(debug: Boolean = false)(implicit scheduler: Scheduler, ec: ExecutionContext) {
  def register(client: Client) = {
    val cid = nextClientId
    _clientWithId.put(cid, client)
    _heartBeat.put(cid, curTime)
    cid
  }
  def heartBeat(clientId: ClientId): Boolean ={
    val exp = expired(clientId)
    if(exp){
      logDebug(s"$clientId 过期，重新注册")
    }else{
      _heartBeat put(clientId, curTime)
      if(_heartBeatBreak containsKey clientId){
        _clientWithId put(clientId, _clientDeadList remove clientId)
        _heartBeatBreak remove clientId
      }
    }
    !exp
  }

  def listClients = _clientWithId.map{ case (ClientId(id), client: Client) =>
    id -> client.name
  }

  def listDevices(clientId: ClientId) = _clientWithId get clientId devices

  def runJob(clientId: Long, deviceId: Long, args: String*) =
    (_clientWithId get ClientId(clientId) ref) ! RunJob(DeviceId(deviceId), args: _*)

  private def expired(clientId: ClientId) =
    !(_heartBeat containsKey clientId) && !(_heartBeatBreak containsKey clientId)

  type Timestamp = Long
  private val _nextClientId = new AtomicLong(0)
  private def nextClientId = ClientId(_nextClientId.getAndIncrement)

  private val _clientWithId = new ConcurrentHashMap[ClientId, Client]
  private val _clientDeadList = new ConcurrentHashMap[ClientId, Client]

  private val _heartBeat = new ConcurrentHashMap[ClientId, Timestamp]
  private val _heartBeatBreak = new ConcurrentHashMap[ClientId, Timestamp]

  private def curTime = System.currentTimeMillis

  private def format(time: Long) =
    new SimpleDateFormat("HH:mm:ss").format(new Date(time))
  private def logDebug(str: String) = if (debug) println(str)

  scheduler.schedule(0 seconds, 3 seconds) {
    logDebug(
      s"""|===================================================
          |curTime -> ${format(curTime)}
          |_printerEndpoint -> ${_clientWithId}
          |_printerDeadList -> ${_clientDeadList}
          |_heartBeatInfo -> ${_heartBeat.mapValues(format)}
          |_printerMaybeDead -> ${_heartBeatBreak.mapValues(format)}
          """.stripMargin)
    _heartBeat.foreach { case (id, lastTime) => if (curTime - lastTime > 3000) {
      logDebug(s"$id 短时间超时，加入低级队列")
      _heartBeat remove id
      _heartBeatBreak.put(id, curTime)
      _clientDeadList put(id, _clientWithId remove id)
    }}
  }

  scheduler.schedule(0 seconds, 10 seconds) {
    logDebug("检测低级心跳队列")
    _heartBeatBreak.toMap.foreach { case (id, lastTime) => logDebug(s"${curTime - lastTime}")
      if (curTime - lastTime > 10000) {
        logDebug(s"[client-$id] 长时间超时，移除")
        _heartBeatBreak remove id
        _clientDeadList remove id
      }
    }
  }
}