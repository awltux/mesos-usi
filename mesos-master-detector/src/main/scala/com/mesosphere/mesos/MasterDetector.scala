package com.mesosphere.mesos

import java.net.URL
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionStage

import com.mesosphere.usi.metrics.Metrics
import com.mesosphere.usi.storage.zookeeper.PersistenceStore.Node
import com.mesosphere.usi.storage.zookeeper.{AsyncCuratorBuilderFactory, AsyncCuratorBuilderSettings, ZooKeeperPersistenceStore}
import com.typesafe.scalalogging.StrictLogging
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryOneTime
import org.apache.mesos.v1.Protos
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.json.Reads._

import scala.async.Async.{async, await}
import scala.compat.java8.FutureConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, SECONDS}
import scala.util.{Failure, Success, Try}

trait MasterDetector {

  /** @return the Mesos master URL for the cluster. */
  def getMaster()(implicit ex: ExecutionContext): CompletionStage[URL]

  /** @return whether the master string is valid. */
  def isValid(): Boolean
}

object MasterDetector {

  /**
    * Constructs a master detector base on the master string passed.
    *
    * @param master The master string should be one of:
    *               host:port
    *               http://host:port
    *               zk://host1:port1,host2:port2,.../path
    *               zk://username:password@host1:port1,host2:port2,.../path
    * @return A master detector.
    */
  def apply(master: String, metrics: Metrics): MasterDetector = {
    if (master.startsWith("zk://")) {
      Zookeeper(master, metrics)
    } else {
      Standalone(master)
    }
  }
}

case class Zookeeper(master: String, metrics: Metrics) extends MasterDetector with StrictLogging {
  require(master.startsWith("zk://"), s"$master does not start with zk://")

  case class ZkUrl(auth: Option[String], servers: String, path: String)
  
  implicit val mesosAddressRead: Reads[Protos.Address] = (
    (JsPath \ "hostname").read[String] ~
      (JsPath \ "ip").read[String] ~
      (JsPath \ "port").read[Int]
  ) { (hostname, ip, port) =>
    Protos.Address.newBuilder().setHostname(hostname).setPort(port).setIp(ip).build()
  }

  // This is the JSON format for the Mesos master information saved in Zookeeper.
  // According to the docs of `setIp`:
  // The IP address (only IPv4) as a packed 4-bytes integer, stored in network order.
  //
  // Scala has no unsigned integer. That means we cannot read eg `3355709612` as int but as long. The `toInt` call
  // converts the long to a signed integer `-939257684`. Note that we do not care about the number but the bytes.
  // According to https://www.scala-lang.org/files/archive/spec/2.11/12-the-scala-standard-library.html#numeric-value-types
  // `toInt` just drops bytes which is what we want.
  implicit val mesosInfoRead: Reads[Protos.MasterInfo] = (
    (JsPath \ "address").read[Protos.Address] ~
      (JsPath \ "hostname").read[String] ~
      (JsPath \ "port").read[Int] ~
      (JsPath \ "id").read[String] ~
      (JsPath \ "ip").read[Long]
  ) { (address, hostname, port, id, ip) =>
    Protos.MasterInfo
      .newBuilder()
      .setAddress(address)
      .setHostname(hostname)
      .setPort(port)
      .setId(id)
      .setIp(ip.toInt)
      .build()
  }

  override def isValid(): Boolean = Try(parse()).map(_ => true).getOrElse(false)

  override def getMaster()(implicit ex: ExecutionContext): CompletionStage[URL] = {
    val ZkUrl(auth, servers, path) = parse()

    val client = {
      val clientBuilder = CuratorFrameworkFactory.builder().connectString(servers).retryPolicy(new RetryOneTime(100))
      auth.foreach(userPassword => clientBuilder.authorization("digest", userPassword.getBytes))
      clientBuilder.build()
    }
    client.start()

    if (
      !client.blockUntilConnected(
        client.getZookeeperClient.getConnectionTimeoutMs,
        java.util.concurrent.TimeUnit.MILLISECONDS
      )
    ) {
      throw new IllegalStateException("Failed to connect to Zookeeper. Will exit now.")
    }

    val clientSettings = AsyncCuratorBuilderSettings(createOptions = Set.empty, compressedData = false)
    val factory: AsyncCuratorBuilderFactory = AsyncCuratorBuilderFactory(client, clientSettings)
    val store: ZooKeeperPersistenceStore = new ZooKeeperPersistenceStore(metrics, factory, parallelism = 1)

    val findMasterUrls: Future[URL] = async {
      val children = await(store.children(path, absolute = false)).get
      logger.info(s"Found Mesos leader node children $children")
      val leader = children.filter(_.startsWith("json.info")).min

      val leaderPath = s"$path/$leader"
      logger.info(s"Connecting to Zookeeper at $servers and fetching Mesos master from $leaderPath.")

      val Node(_, bytes) = await(store.read(leaderPath)).get
      logger.info(s"Mesos leader data: ${bytes.decodeString(StandardCharsets.UTF_8)}")

      // TODO: Rather than testing connections, masterInfo should tell us whether it's using http or https
      val masterInfo = parserMasterInfo(bytes.decodeString(StandardCharsets.UTF_8))

      // common part of mesos URL
      val partialMasterUrl = "://" + masterInfo.getAddress.getHostname + ":" + masterInfo.getAddress.getPort

      // Attempt to connect to a URL
      def checkHealthUrl(masterUrl: URL): Future[URL] = Future {
        // Use simple endpoint to check connection to Mesos Master
        val healthUrl = new URL(masterUrl + "/health")
        val connection :HttpURLConnection = healthUrl.openConnection().asInstanceOf[HttpURLConnection]
        connection.setConnectTimeout(10000)
        connection.setReadTimeout(10000)
        // Only interested in the HEADER info i.e ResponseCode
        connection.setRequestMethod("HEAD")

        // An Exception will indicate Failure.
        connection.connect()

        val responseCode = connection.getResponseCode
        if (responseCode != 200) {
          throw new IOException("Connection to '/health' returned unexpected responseCode = " + responseCode)
        }

        // return the input parameter
        masterUrl
      }

      // One of these URLs should may be valid
      val masterUrlList = List(
        new URL("http"  + partialMasterUrl),
        new URL("https" + partialMasterUrl)
      )

      // Create a list of futures that check for valid url connections
      val futuresList = for ( url <- masterUrlList ) yield checkHealthUrl( url )

      // This will hold the successful Master URL
      var masterUrl:URL = null
      // As each future completes, check its status and keep successful URL
      futuresList.foreach(f => f.onComplete {
        case Success(successfulURL) => masterUrl = successfulURL
        // Ignore Failed connections
        case Failure(_) => Nil
      })

      // Wait for all connection tests to complete
      // Because both connections go to the same port, they should be quick to return.
      // Add a timeout just in-case Mesos isn't listening
      futuresList.map(f => Await.ready(f, Duration(30, SECONDS)))

      masterUrl
    }

    // Ensure Zookeeper client is closed.
    findMasterUrls.onComplete {
      case Failure(t) =>
        logger.error("Failed to get Mesos master leader node from ZK: ", t)
        client.close()
      case Success(_) =>
        client.close()
    }

    findMasterUrls.toJava
  }

  /** @return the parsed [[MasterInfo]] from the ZooKeeper node data. */
  def parserMasterInfo(input: String): Protos.MasterInfo = Json.parse(input).as[Protos.MasterInfo]

  /** @return proper Zookeeper connection string as per {@link ZooKeeper#ZooKeeper(String, int, Watcher)} etc. */
  def parse(): ZkUrl = {
    // Strip leading zk://
    val stripped = master.substring(5)

    // Extract path
    val pathIndex = stripped.indexOf('/')
    val path = if (pathIndex < 0) "/" else stripped.substring(pathIndex, stripped.length)

    // Find optional authentication
    val endIndex = if (pathIndex < 0) stripped.length else pathIndex
    stripped.substring(0, endIndex).split('@') match {
      case Array(auth, servers) => ZkUrl(Some(auth), servers, path)
      case Array(servers) => ZkUrl(None, servers, path)
      case _ => throw new IllegalArgumentException(s"$master contained more than one authentication.")
    }
  }
}

case class Standalone(master: String) extends MasterDetector {
  def url: URL = if (master.startsWith("http") || master.startsWith("https")) new URL(master) else new URL(s"http://$master")

  override def isValid(): Boolean = Try(url).map(_ => true).getOrElse(false)

  override def getMaster()(implicit ex: ExecutionContext): CompletionStage[URL] = Future.successful(url).toJava
}

