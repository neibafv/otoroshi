package auth

import controllers.routes
import env.Env
import models._
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Results._
import play.api.mvc.{RequestHeader, Result}
import security.{Auth0Config, IdGenerator}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

sealed trait AuthModule {

  def loginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result]

  def logout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Unit]

  def callback(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]]
}

trait AuthModuleConfig extends AsJson {
  def clientId: String
  def clientSecret: String
  def authorizeUrl: String
  def tokenUrl: String
  def userInfoUrl: String
  def loginUrl: String
  def logoutUrl: String
  def accessTokenField: String
  def nameField: String
  def emailField: String
  def callbackUrl: String
  def authModule(config: GlobalConfig): AuthModule
}

object AuthModuleConfig extends FromJson[AuthModuleConfig] {
  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] = Try {
    (json \ "type").as[String] match {
      case "oauth2"              => Oauth2AuthModuleConfig.fromJson(json)
      case "global-auth0"        => GlobalConfigAuth0AuthModuleConfig.fromJson(json)
      case "actual-global-auth0" => GlobalConfigAuth0AuthModuleConfig.fromJson(json)
    }
  } recover {
    case e => Left(e)
  } get
}


object GlobalConfigAuth0AuthModuleConfig extends FromJson[AuthModuleConfig] {
  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] = Right(RefAuth0AuthModuleConfig())
}

case class RefAuth0AuthModuleConfig() extends AuthModuleConfig {
  override def clientId = ???
  override def clientSecret = ???
  override def authorizeUrl = ???
  override def tokenUrl = ???
  override def userInfoUrl = ???
  override def loginUrl = ???
  override def logoutUrl = ???
  override def accessTokenField = ???
  override def nameField = ???
  override def emailField = ???
  override def callbackUrl = ???
  override def authModule(config: GlobalConfig): AuthModule = GenericOauth2Module(GlobalConfigAuth0AuthModuleConfig(config))
  override def asJson = Json.obj(
    "type" -> "global-auth0"
  )
}

case class GlobalConfigAuth0AuthModuleConfig(config: GlobalConfig) extends AuthModuleConfig {

  val auth0Config = config.privateAppsAuth0Config.getOrElse(Auth0Config(
    secret = "secret",
    clientId = "client",
    callbackURL = "http://privateapps.foo.bar:8080/privateapps/generic/callback",
    domain = "https://mydomain.eu.auth0.com"
  ))

  val domain = auth0Config.domain

  override def clientId: String = auth0Config.clientId
  override def clientSecret: String = auth0Config.secret
  override def authorizeUrl = s"$domain/authorize"
  override def tokenUrl = s"$domain/oauth/token"
  override def userInfoUrl = s"$domain/userinfo"
  override def loginUrl = s"$domain/authorize"
  override def logoutUrl = s"$domain/logout"
  override def accessTokenField: String = "access_token"
  override def nameField: String = "name"
  override def emailField: String = "email"
  override def callbackUrl: String = auth0Config.callbackURL
  override def authModule(config: GlobalConfig): AuthModule = GenericOauth2Module(this)
  override def asJson: JsValue = Json.obj(
    "type" -> "actual-global-auth0"
  )
}

object Oauth2AuthModuleConfig extends FromJson[AuthModuleConfig] {
  override def fromJson(json: JsValue): Either[Throwable, AuthModuleConfig] = Try {
    Right(Oauth2AuthModuleConfig(
      clientId = (json \ "clientId").asOpt[String].getOrElse("client"),
      clientSecret = (json \ "clientSecret").asOpt[String].getOrElse("secret"),
      authorizeUrl = (json \ "authorizeUrl").asOpt[String].getOrElse("http://localhost:8082/oauth/authorize"),
      tokenUrl = (json \ "tokenUrl").asOpt[String].getOrElse("http://localhost:8082/oauth/token"),
      userInfoUrl = (json \ "userInfoUrl").asOpt[String].getOrElse("http://localhost:8082/userinfo"),
      loginUrl = (json \ "loginUrl").asOpt[String].getOrElse("http://localhost:8082/login"),
      logoutUrl = (json \ "logoutUrl").asOpt[String].getOrElse("http://localhost:8082/logout"),
      accessTokenField = (json \ "accessTokenField").asOpt[String].getOrElse("access_token"),
      nameField = (json \ "nameField").asOpt[String].getOrElse("name"),
      emailField = (json \ "emailField").asOpt[String].getOrElse("email"),
      callbackUrl = (json \ "callbackUrl").asOpt[String].getOrElse("http://privateapps.foo.bar:8080/privateapps/generic/callback")
    ))
  } recover {
    case e => Left(e)
  } get
}

case class Oauth2AuthModuleConfig(
  clientId: String = "client",
  clientSecret: String = "secret",
  tokenUrl: String = "http://localhost:8082/oauth/token",
  authorizeUrl: String = "http://localhost:8082/oauth/authorize",
  userInfoUrl: String = "http://localhost:8082/userinfo",
  loginUrl: String = "http://localhost:8082/login",
  logoutUrl: String = "http://localhost:8082/logout",
  accessTokenField: String = "access_token",
  nameField: String = "name",
  emailField: String = "email",
  callbackUrl: String = "http://privateapps.foo.bar:8080/privateapps/generic/callback"
) extends AuthModuleConfig {
  override def authModule(config: GlobalConfig): AuthModule = GenericOauth2Module(this)
  override def asJson: JsValue = Json.obj(
    "type" -> "oauth2",
    "clientId" -> this.clientId,
    "clientSecret" -> this.clientSecret,
    "authorizeUrl" -> this.authorizeUrl,
    "tokenUrl" -> this.tokenUrl,
    "userInfoUrl" -> this.userInfoUrl,
    "loginUrl" -> this.loginUrl,
    "logoutUrl" -> this.logoutUrl,
    "accessTokenField" -> this.accessTokenField,
    "nameField" -> this.nameField,
    "emailField" -> this.emailField,
    "callbackUrl" -> this.callbackUrl
  )
}

case class GenericOauth2Module(authConfig: AuthModuleConfig) extends AuthModule {

  import play.api.libs.ws.DefaultBodyWritables._
  import utils.future.Implicits._

  override def loginPage(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val req = request

    val redirect = request.getQueryString("redirect")
    val clientId = authConfig.clientId
    val responseType = "code"
    val scope = "openid profile email name"

    val redirectUri = authConfig.callbackUrl + s"?desc=${descriptor.id}"
    val loginUrl = s"${authConfig.loginUrl}?scope=$scope&client_id=$clientId&response_type=$responseType&redirect_uri=$redirectUri"
    println(loginUrl)
    Redirect(
      loginUrl
    ).addingToSession(
      "pa-redirect-after-login" -> redirect.getOrElse(
        routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
      )
    ).asFuture
  }

  override def logout(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    // TODO: implements
    ().asFuture
  }

  override def callback(request: RequestHeader, config: GlobalConfig, descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Future[Either[String, PrivateAppsUser]] = {
    val clientId = authConfig.clientId
    val clientSecret = authConfig.clientSecret
    val redirectUri = authConfig.callbackUrl + s"?desc=${descriptor.id}"
    request.getQueryString("error") match {
      case Some(error) => Left(error).asFuture
      case None => {
        request.getQueryString("code") match {
          case None => Left("No code :(").asFuture
          case Some(code) => {
            env.Ws.url(authConfig.tokenUrl)
              .post(
                Map(
                  "code" -> code,
                  "grant_type" -> "authorization_code",
                  "client_id" -> clientId,
                  "client_secret" -> clientSecret,
                  "redirect_uri" -> redirectUri
                )
              )(writeableOf_urlEncodedSimpleForm).flatMap { resp =>
              val accessToken = (resp.json \ authConfig.accessTokenField).as[String]
              env.Ws.url(authConfig.userInfoUrl)
                .post(Map(
                  "access_token" -> accessToken
                ))(writeableOf_urlEncodedSimpleForm).map(_.json)
            }.map { user =>
              Right(
                PrivateAppsUser(
                  randomId = IdGenerator.token(64),
                  name = (user \ authConfig.nameField).as[String],
                  email = (user \ authConfig.emailField).as[String],
                  profile = user
                )
              )
            }
          }
        }
      }
    }
  }
}
