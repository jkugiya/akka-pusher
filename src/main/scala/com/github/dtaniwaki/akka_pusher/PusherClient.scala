package com.github.dtaniwaki.akka_pusher

import spray.json._
import spray.http.Uri
import scala.concurrent.{ ExecutionContext, Future, Promise, Await }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Failure, Success, Try }
import akka.actor.ActorSystem
import akka.stream.{Materializer, ActorMaterializer}
import akka.stream.scaladsl.{ Source, Flow, Sink }
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.HttpProtocols._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.Http
import akka.http.scaladsl.HttpsContext
import com.typesafe.config.{ConfigFactory, Config}
import com.typesafe.scalalogging.StrictLogging

import Utils._
import PusherModels._
import PusherExceptions._

class PusherClient(config: Config = ConfigFactory.load())(implicit val system: ActorSystem = ActorSystem("pusher")) extends PusherJsonSupport
  with StrictLogging
  with PusherValidator
{
  private val host = "api.pusherapp.com"
  val appId = config.getString("pusher.appId")
  val key = config.getString("pusher.key")
  val secret = config.getString("pusher.secret")
  private val ssl = if (config.hasPath("pusher.ssl"))
    config.getBoolean("pusher.ssl")
  else
    false

  implicit val materializer = ActorMaterializer()
  private val pool = if (ssl)
    Http(system).cachedHostConnectionPoolTls[Int](host)
  else
    Http(system).cachedHostConnectionPool[Int](host)
  private val scheme = if (ssl)
    "https"
  else
    "http"

  def trigger(event: String, channel: String, message: String, socketId: Option[String] = None): Future[Result] = {
    validateChannel(channel)
    socketId.map(validateSocketId)
    var uri = generateUri(path = Uri.Path(s"/apps/$appId/events"))

    val data = Map(
      "data" -> Some(Map("message" -> message).toJson.toString),
      "name" -> Some(event),
      "channel" -> Some(channel),
      "socket_id" -> socketId
    ).filter(_._2.isDefined).mapValues(_.get)

    uri = signUri("POST", uri, Some(data))

    request(HttpRequest(method = POST, uri = uri.toString, entity = HttpEntity(ContentType(`application/json`), data.toJson.toString))).map{ new Result(_) }
  }

  def channel(channel: String, attributes: Option[Seq[String]] = None): Future[Channel] = {
    validateChannel(channel)
    var uri = generateUri(path = Uri.Path(s"/apps/$appId/channels/$channel"))

    val params = Map(
      "info" -> attributes.map(_.mkString(","))
    ).filter(_._2.isDefined).mapValues(_.get)

    uri = signUri("GET", uri.withQuery(params))

    request(HttpRequest(method = GET, uri = uri.toString)).map{ new  Channel(_) }
  }

  def channels(prefixFilter: String, attributes: Option[Seq[String]] = None): Future[Channels] = {
    var uri = generateUri(path = Uri.Path(s"/apps/$appId/channels"))

    val params = Map(
      "filter_by_prefix" -> Some(prefixFilter),
      "info" -> attributes.map(_.mkString(","))
    ).filter(_._2.isDefined).mapValues(_.get)

    uri = signUri("GET", uri.withQuery(params))

    request(HttpRequest(method = GET, uri = uri.toString)).map{ new Channels(_) }
  }

  def users(channel: String): Future[Users] = {
    validateChannel(channel)
    var uri = generateUri(path = Uri.Path(s"/apps/$appId/channels/$channel/users"))
    uri = signUri("GET", uri)

    request(HttpRequest(method = GET, uri = uri.toString)).map{ new Users(_) }
  }

  def authenticate(channel: String, socketId: String, data: Option[ChannelData] = None): AuthenticatedParams = {
    val serializedData = data.map(_.toJson.compactPrint)
    val signingStrings = serializedData.foldLeft(List(socketId, channel))(_ :+ _)
    AuthenticatedParams(s"$key:${signature(signingStrings.mkString(":"))}", serializedData)
  }

  def validateSignature(_key: String, _signature: String, body: String): Boolean = {
    key == _key && signature(body) == _signature
  }

  def request(req: HttpRequest): Future[String] = {
    Source.single(req, 0)
    .via(pool)
    .runWith(Sink.head)
    .flatMap {
      case (Success(response), _) =>
        response.entity.withContentType(ContentTypes.`application/json`)
        .toStrict(5 seconds)
        .map(_.data.decodeString(response.entity.contentType.charset.value))
        .map { body =>
          response.status match {
            case StatusCodes.OK => body
            case StatusCodes.BadRequest => throw new BadRequest(body)
            case StatusCodes.Unauthorized => throw new Unauthorized(body)
            case StatusCodes.Forbidden => throw new Forbidden(body)
            case _ => throw new PusherException(body)
          }
        }
      case _ =>
        throw new PusherException("Pusher request failed")
    }
  }

  private def generateUri(path: Uri.Path): Uri = {
    Uri(scheme = scheme, authority = Uri.Authority(Uri.Host(host)), path = path)
  }

  private def signUri(method: String, uri: Uri, data: Option[Map[String, String]] = None): Uri = {
    var signedUri = uri
    var params = List(
      ("auth_key", key),
      ("auth_timestamp", (System.currentTimeMillis / 1000).toString),
      ("auth_version", "1.0")
    )
    if (data.isDefined) {
      val serializedData = data.get.toJson.toString
      params = params :+ ("body_md5", md5(serializedData))
    }
    signedUri = signedUri.withQuery(params ++ uri.query.toList: _*)

    val signingString = s"$method\n${uri.path}\n${signedUri.query.toString}"
    signedUri.withQuery(signedUri.query.toList :+ ("auth_signature", signature(signingString)): _*)
  }

  private def signature(value: String): String = {
    sha256(secret, value)
  }
}
