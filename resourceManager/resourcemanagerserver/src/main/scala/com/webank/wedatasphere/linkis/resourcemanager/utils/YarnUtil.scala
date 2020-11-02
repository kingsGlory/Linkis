/*
 * Copyright 2019 WeBank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.wedatasphere.linkis.resourcemanager.utils

import java.net.ConnectException

import com.fasterxml.jackson.core.JsonParseException
import com.webank.wedatasphere.linkis.common.conf.CommonVars
import com.webank.wedatasphere.linkis.common.conf.Configuration.hadoopConfDir
import com.webank.wedatasphere.linkis.common.utils.{Logging, Utils}
import com.webank.wedatasphere.linkis.resourcemanager.YarnResource
import com.webank.wedatasphere.linkis.resourcemanager.exception.{RMErrorException, RMFatalException, RMWarnException}
import org.apache.commons.lang.StringUtils
import org.apache.hadoop.fs.Path
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.util.RMHAUtils
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods._
import org.json4s.{DefaultFormats, JValue}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
/**
  * Created by shanhuang on 2018/9/24.
  */
object YarnUtil extends Logging{


  implicit val format = DefaultFormats

  private var yarnConf: YarnConfiguration = _

  private var rm_web_address: String = CommonVars("wds.linkis.yarn.rm.web.address", "").getValue

  private var hadoop_version:String = "2.7.2"

  private val httpClient = HttpClients.createDefault()

  def init() = {
    if(StringUtils.isBlank(this.rm_web_address)){
      yarnConf = new YarnConfiguration()
      yarnConf.addResource(new Path(hadoopConfDir, YarnConfiguration.CORE_SITE_CONFIGURATION_FILE))
      yarnConf.addResource(new Path(hadoopConfDir, YarnConfiguration.YARN_SITE_CONFIGURATION_FILE))
      reloadRMWebAddress()
    }
    info(s"This yarn  rm web address is:${this.rm_web_address}")
    Utils.tryAndErrorMsg(getHadoopVersion())("Failed to get HadoopVersion")
  }

  init()

  private def reloadRMWebAddress() = {

    val rmHAId = RMHAUtils.findActiveRMHAId(yarnConf)
    if(rmHAId == null) {
      if(StringUtils.isNotEmpty(this.rm_web_address)) {
        info(s"cannot find RM_HA_ID, instead of the old rm_web_address ${this.rm_web_address}, now try to failover to the another one.")
        val rm_web_address = RMHAUtils.getRMHAWebappAddresses(yarnConf).filterNot(this.rm_web_address.contains).head
        this.rm_web_address = if(rm_web_address.startsWith("http")) rm_web_address
        else if(YarnConfiguration.useHttps(yarnConf)) "https://" + rm_web_address else "http://" + rm_web_address
      } else {
        info("cannot find RM_HA_ID, will try to load the right rm_web_address by send http requests.")
        RMHAUtils.getRMHAWebappAddresses(yarnConf).map(f => if(f.startsWith("http")) f
        else if(YarnConfiguration.useHttps(yarnConf)) "https://" + f else "http://" + f).foreach { f =>
          this.rm_web_address = f
          info(s"the first, use $rm_web_address to ensure the right rm_web_address.")
        }
      }
      if(StringUtils.isEmpty(this.rm_web_address)){
        val yarnWebUrl = yarnConf.get("yarn.resourcemanager.webapp.address")
        if(StringUtils.isEmpty(yarnWebUrl)) {
          val yarnHttps = yarnConf.get("yarn.resourcemanager.webapp.https.address")
          if(StringUtils.isEmpty(yarnHttps)){
            throw new RMFatalException(11005,"Cannot find yarn resourcemanager restful address,please to configure yarn-site.xml")
          } else {
            this.rm_web_address  = if(yarnHttps.startsWith("https")) yarnHttps else "https://" + yarnHttps
          }
        } else{
          this.rm_web_address  = if(yarnWebUrl.startsWith("http")) yarnWebUrl else "http://" + yarnWebUrl
        }
      }
    } else {
      info(s"find RM_HA_ID $rmHAId, will try to load the right rm_web_address from HA mode.")
      yarnConf.set(YarnConfiguration.RM_HA_ID, rmHAId)
      val socketAddress = yarnConf.getSocketAddr(YarnConfiguration.RM_WEBAPP_ADDRESS,
        YarnConfiguration.DEFAULT_RM_WEBAPP_ADDRESS, YarnConfiguration.DEFAULT_RM_WEBAPP_PORT)
      val rm_web_address = socketAddress.getHostName + ":" + socketAddress.getPort
      this.rm_web_address = if(YarnConfiguration.useHttps(yarnConf)) "https://"  + rm_web_address else "http://" + rm_web_address
    }
    info(s"Resource Manager WebApp address: $rm_web_address.")
  }

  private def getResponseByUrl(url: String): JValue = {
    val httpGet = new HttpGet(rm_web_address + "/ws/v1/cluster/" + url)
    httpGet.addHeader("Accept", "application/json")
    val response = httpClient.execute(httpGet)
    parse(EntityUtils.toString(response.getEntity()))
  }

  private def getHadoopVersion():Unit = {
    val resourceManagerVersion = getResponseByUrl("info") \ "clusterInfo" \ "resourceManagerVersion"

    info(s"Hadoop version is $resourceManagerVersion")

    hadoop_version = resourceManagerVersion.values.asInstanceOf[String]
  }


  def getQueueInfo(queueName: String): (YarnResource, YarnResource) = {

    def getYarnResource(jValue: Option[JValue]): Option[YarnResource] = {
      jValue.map(r => new YarnResource((r \ "memory").asInstanceOf[JInt].values.toLong * 1024l * 1024l, (r \ "vCores").asInstanceOf[JInt].values.toInt, 0, queueName))
    }

    def maxEffectiveHandle(queueValue: Option[JValue]): Option[YarnResource] = {
      val metrics = getResponseByUrl( "metrics")
      val totalResouceInfoResponse = ((metrics \ "clusterMetrics" \ "totalMB").asInstanceOf[JInt].values.toLong, (metrics \ "clusterMetrics" \ "totalVirtualCores").asInstanceOf[JInt].values.toLong)
      queueValue.map(r => {
//        val effectiveResource = (r \ "absoluteCapacity").asInstanceOf[JDecimal].values.toDouble- (r \ "absoluteUsedCapacity").asInstanceOf[JDecimal].values.toDouble
      val effectiveResource = (r \ "absoluteCapacity").extract[Double]-  (r \ "absoluteUsedCapacity").extract[Double]
        new YarnResource(math.floor(effectiveResource * totalResouceInfoResponse._1 * 1024l * 1024l/100).toLong, math.floor(effectiveResource * totalResouceInfoResponse._2/100).toInt, 0, queueName)
      })
    }

    var realQueueName = "root." + queueName
    def getQueue(queues: JValue): Option[JValue] = queues match {
      case JArray(queue) =>
        queue.foreach { q =>
          val yarnQueueName = (q \ "queueName").asInstanceOf[JString].values
          if(yarnQueueName == realQueueName) return Some(q)
          else if(realQueueName.startsWith(yarnQueueName + ".")) return getQueue(getChildQueues(q))
        }
        None
      case JObject(queue) =>
        if(queue.find(_._1 == "queueName").exists(_._2.asInstanceOf[JString].values == realQueueName)) Some(queues)
        else {
          val childQueues = queue.find(_._1 == "childQueues")
          if(childQueues.isEmpty) None
          else getQueue(childQueues.map(_._2).get)
        }
      case JNull | JNothing => None
    }
    def getChildQueues(resp:JValue):JValue =  {
      val queues = resp \ "childQueues" \ "queue"
      if(queues != null && queues != JNull && queues != JNothing && queues.children.nonEmpty) {
        info(s"test queue:$queues")
        queues
      } else resp  \ "childQueues"
    }

    def getQueueOfCapacity(queues: JValue): Option[JValue] = {
      queues match {
        case JArray(queue) =>
          queue.foreach { q =>
            val yarnQueueName = (q \ "queueName").asInstanceOf[JString].values
            if(yarnQueueName == realQueueName) return Some(q)
            else if((q \ "queues").toOption.nonEmpty) {
              val matchQueue = getQueueOfCapacity(getChildQueuesOfCapacity(q))
              if (matchQueue.nonEmpty) return matchQueue
            }
          }
          None
        case JObject(queue) =>
          if(queue.find(_._1 == "queueName").exists(_._2.asInstanceOf[JString].values == realQueueName)) return Some(queues)
          else if((queues \ "queues").toOption.nonEmpty) {
            val matchQueue = getQueueOfCapacity(getChildQueuesOfCapacity(queues))
            if (matchQueue.nonEmpty) return matchQueue
          }
          None
        case JNull | JNothing => None
      }
    }

    def getChildQueuesOfCapacity(resp:JValue):JValue = resp \ "queues" \ "queue"

    def getResources(): (YarnResource, YarnResource) = {
      val resp = getResponseByUrl("scheduler")
      val schedulerType = (resp \ "scheduler" \ "schedulerInfo" \ "type").asInstanceOf[JString].values
      if ("capacityScheduler".equals(schedulerType)) {
        realQueueName = queueName
        val childQueues = getChildQueuesOfCapacity(resp \ "scheduler" \ "schedulerInfo")
        val queue = getQueueOfCapacity(childQueues)
        if (queue.isEmpty) {
          debug(s"cannot find any information about queue $queueName, response: " + resp)
          throw new RMWarnException(11006, s"queue $queueName is not exists in YARN.")
        }
        val memory = queue.map( _ \ "capacities" \ "queueCapacitiesByPartition" \ "configuredMaxResource" \ "memory") match {
          case Some(JArray(List(JInt(x)))) => x.toLong * 1024 * 1024
        }
        val vCores = queue.map( _ \ "capacities" \ "queueCapacitiesByPartition" \ "configuredMaxResource" \ "vCores") match {
          case Some(JArray(List(JInt(x)))) => x.toInt
        }
        (new YarnResource(memory, vCores, 0, queueName),
          getYarnResource(queue.map( _ \ "resourcesUsed")).get)

//        (getYarnResource(queue.map( _ \ "capacities" \ "queueCapacitiesByPartition" \ "configuredMaxResource")).get,
//          getYarnResource(queue.map( _ \ "resourcesUsed")).get)
//        (maxEffectiveHandle(queue).get, getYarnResource(queue.map( _ \ "resourcesUsed")).get)
      } else if ("fairScheduler".equals(schedulerType)) {
        val childQueues = getChildQueues(resp \ "scheduler" \ "schedulerInfo" \ "rootQueue")
        val queue = getQueue(childQueues)
        if (queue.isEmpty) {
          debug(s"cannot find any information about queue $queueName, response: " + resp)
          throw new RMWarnException(11006, s"queue $queueName is not exists in YARN.")
        }
        (getYarnResource(queue.map(_ \ "maxResources")).get,
          getYarnResource(queue.map(_ \ "usedResources")).get)
      } else {
        debug(s"only support fairScheduler or capacityScheduler, schedulerType: $schedulerType , response: " + resp)
        throw new RMWarnException(11006, s"only support fairScheduler or capacityScheduler, schedulerType: $schedulerType")
      }
    }

    Utils.tryCatch(getResources())(t => {
      if ((t.getCause.isInstanceOf[JsonParseException] && t.getCause.getMessage.contains("This is standby RM"))
        || t.getCause.isInstanceOf[ConnectException]) {
        reloadRMWebAddress()
        getQueueInfo(queueName)
      } else throw new RMErrorException(11006, "Get the Yarn queue information exception" +
        ".(获取Yarn队列信息异常)", t)
    })
  }

  def getApplicationsInfo(queueName: String): Array[YarnAppInfo] = {


    def getYarnResource(jValue: Option[JValue]): Option[YarnResource] = {
      jValue.map(r => new YarnResource((r \ "allocatedMB").asInstanceOf[JInt].values.toLong * 1024l * 1024l, (r \ "allocatedVCores").asInstanceOf[JInt].values.toInt, 0, queueName))
    }

    val realQueueName = "root." + queueName

    def getAppInfos(): Array[YarnAppInfo] = {
      val resp = getResponseByUrl("apps")
      resp \ "apps" \ "app" match {
        case JArray(apps) =>
          val appInfoBuffer = new ArrayBuffer[YarnAppInfo]()
          apps.foreach { app =>
            val yarnQueueName = (app \ "queue").asInstanceOf[JString].values
            val state = (app \ "state").asInstanceOf[JString].values
            if (yarnQueueName == realQueueName && (state == "RUNNING" || state == "ACCEPTED")) {
              val appInfo = new YarnAppInfo(
                (app \ "id").asInstanceOf[JString].values,
                (app \ "user").asInstanceOf[JString].values,
                state,
                (app \ "applicationType").asInstanceOf[JString].values,
                getYarnResource(Some(app)).get
              )
              appInfoBuffer.append(appInfo)
            }
          }
          appInfoBuffer.toArray
        case JNull | JNothing => new Array[YarnAppInfo](0)
      }
    }

    Utils.tryCatch(getAppInfos())(t => {
      if ((t.getCause.isInstanceOf[JsonParseException] && t.getCause.getMessage.contains("This is standby RM"))
        || t.getCause.isInstanceOf[ConnectException]) {
        reloadRMWebAddress()
        getApplicationsInfo(queueName)
      } else throw new RMErrorException(11006, "Get the Yarn Application information exception.(获取Yarn Application信息异常)", t)
    })
  }


}

