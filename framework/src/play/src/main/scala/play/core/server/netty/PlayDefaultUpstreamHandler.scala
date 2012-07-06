package play.core.server.netty

import org.jboss.netty.buffer._
import org.jboss.netty.channel._
import org.jboss.netty.bootstrap._
import org.jboss.netty.channel.Channels._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel.socket.nio._
import org.jboss.netty.handler.stream._
import org.jboss.netty.handler.codec.http.HttpHeaders._
import org.jboss.netty.handler.codec.http.HttpHeaders.Names._
import org.jboss.netty.handler.codec.http.HttpHeaders.Values._
import org.jboss.netty.handler.ssl._

import org.jboss.netty.channel.group._
import java.util.concurrent._
import play.core._
import server.Server
import play.api._
import play.api.mvc._
import play.api.http.HeaderNames.X_FORWARDED_FOR
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input._
import play.api.libs.concurrent._
import scala.collection.JavaConverters._

import play.api.libs.concurrent.execution.defaultContext

private[server] class PlayDefaultUpstreamHandler(server: Server, allChannels: DefaultChannelGroup) extends SimpleChannelUpstreamHandler with Helpers with WebSocketHandler with RequestBodyHandler {

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    Logger.trace("Exception caught in Netty", e.getCause)
    e.getChannel.close()
  }

  override def channelConnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    Option(ctx.getPipeline.get(classOf[SslHandler])).map { sslHandler =>
      sslHandler.handshake()
    }
  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    allChannels.add(e.getChannel)
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    e.getMessage match {

      case nettyHttpRequest: HttpRequest =>

        Logger("play").trace("Http request received by netty: " + nettyHttpRequest)

        val keepAlive = isKeepAlive(nettyHttpRequest)
        val websocketableRequest = websocketable(nettyHttpRequest)
        var version = nettyHttpRequest.getProtocolVersion
        val nettyUri = new QueryStringDecoder(nettyHttpRequest.getUri)
        val parameters = Map.empty[String, Seq[String]] ++ nettyUri.getParameters.asScala.mapValues(_.asScala)

        val rHeaders = getHeaders(nettyHttpRequest)
        val rCookies = getCookies(nettyHttpRequest)

        def rRemoteAddress = e.getRemoteAddress match {
          case ra: java.net.InetSocketAddress => {
            val remoteAddress = ra.getAddress.getHostAddress
            (for {
              xff <- rHeaders.get(X_FORWARDED_FOR)
              app <- server.applicationProvider.get.right.toOption
              trustxforwarded <- app.configuration.getBoolean("trustxforwarded").orElse(Some(false))
              if remoteAddress == "127.0.0.1" || trustxforwarded
            } yield xff).getOrElse(remoteAddress)
          }
        }

        import org.jboss.netty.util.CharsetUtil;

        //mapping netty request to Play's

        val requestHeader = new RequestHeader {
          def uri = nettyHttpRequest.getUri
          def path = nettyUri.getPath
          def method = nettyHttpRequest.getMethod.getName
          def queryString = parameters
          def headers = rHeaders
          lazy val remoteAddress = rRemoteAddress
          def username = None
        }

        // converting netty response to play's
        val response = new Response {

          def handle(result: Result) {
            result match {

              case AsyncResult(p) => p.extend1 {
                case Redeemed(v) => handle(v)
                case Thrown(e) => {
                  server.applicationProvider.get match {
                    case Right(app) => handle(app.handleError(requestHeader, e))
                    case Left(_) => handle(Results.InternalServerError)
                  }
                }
              }

              case r @ SimpleResult(ResponseHeader(status, headers), body) if (!websocketableRequest.check) => {
                val nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status))

                Logger("play").trace("Sending simple result: " + r)

                // Set response headers
                headers.filterNot(_ == (CONTENT_LENGTH, "-1")).foreach {

                  // Fix a bug for Set-Cookie header. 
                  // Multiple cookies could be merged in a single header
                  // but it's not properly supported by some browsers
                  case (name @ play.api.http.HeaderNames.SET_COOKIE, value) => {
                    nettyResponse.setHeader(name, Cookies.decode(value).map { c => Cookies.encode(Seq(c)) }.asJava)
                  }

                  case (name, value) => nettyResponse.setHeader(name, value)
                }

                // Response header Connection: Keep-Alive is needed for HTTP 1.0
                if (keepAlive && version == HttpVersion.HTTP_1_0) {
                  nettyResponse.setHeader(CONNECTION, KEEP_ALIVE)
                }

                // Stream the result
                headers.get(CONTENT_LENGTH).map { contentLength =>

                  val writer: Function1[r.BODY_CONTENT, Promise[Unit]] = x => {
                    if (e.getChannel.isConnected())
                      NettyPromise(e.getChannel.write(ChannelBuffers.wrappedBuffer(r.writeable.transform(x))))
                        .extend1 { case Redeemed(()) => (); case Thrown(ex) => Logger("play").debug(ex.toString) }
                    else Promise.pure(())
                  }

                  val bodyIteratee = {
                    val writeIteratee = Iteratee.fold1(
                      if (e.getChannel.isConnected())
                        NettyPromise(e.getChannel.write(nettyResponse))
                        .extend1 { case Redeemed(()) => (); case Thrown(ex) => Logger("play").debug(ex.toString) }
                      else Promise.pure(()))((_, e: r.BODY_CONTENT) => writer(e))

                    Enumeratee.breakE[r.BODY_CONTENT](_ => !e.getChannel.isConnected()).transform(writeIteratee).mapDone { _ =>
                      if (e.getChannel.isConnected()) {
                        if (!keepAlive) e.getChannel.close()
                      }
                    }
                  }

                  body(bodyIteratee)

                }.getOrElse {

                  // No Content-Length header specified, buffer in-memory
                  val channelBuffer = ChannelBuffers.dynamicBuffer(512)
                  val writer: Function2[ChannelBuffer, r.BODY_CONTENT, Unit] = (c, x) => c.writeBytes(r.writeable.transform(x))
                  val stringIteratee = Iteratee.fold(channelBuffer)((c, e: r.BODY_CONTENT) => { writer(c, e); c })
                  val p = body |>> stringIteratee
                  p.flatMap(i => i.run)
                    .extend1 { 
                      case Redeemed(buffer) =>
                        nettyResponse.setHeader(CONTENT_LENGTH, channelBuffer.readableBytes)
                        nettyResponse.setContent(buffer)
                        val f = e.getChannel.write(nettyResponse)
                        if (!keepAlive) f.addListener(ChannelFutureListener.CLOSE)
                    }
                }

              }

              case r @ ChunkedResult(ResponseHeader(status, headers), chunks) => {

                Logger("play").trace("Sending chunked result: " + r)

                val nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status))

                // Copy headers to netty response
                headers.foreach {

                  // Fix a bug for Set-Cookie header. 
                  // Multiple cookies could be merged in a single header
                  // but it's not properly supported by some browsers
                  case (name @ play.api.http.HeaderNames.SET_COOKIE, value) => {

                    import scala.collection.JavaConverters._
                    import play.api.mvc._

                    nettyResponse.setHeader(name, Cookies.decode(value).map { c => Cookies.encode(Seq(c)) }.asJava)

                  }

                  case (name, value) => nettyResponse.setHeader(name, value)
                }

                nettyResponse.setHeader(TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
                nettyResponse.setChunked(true)

                val writer: Function1[r.BODY_CONTENT, Promise[Unit]] = x => {
                  if (e.getChannel.isConnected())
                    NettyPromise(e.getChannel.write(new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(r.writeable.transform(x)))))
                      .extend1 { case Redeemed(()) => (); case Thrown(ex) => Logger("play").debug(ex.toString) }
                  else Promise.pure(())
                }

                val chunksIteratee = {
                  val writeIteratee = Iteratee.fold1(
                    if (e.getChannel.isConnected())
                      NettyPromise(e.getChannel.write(nettyResponse))
                      .extend1 { case Redeemed(()) => (); case Thrown(ex) => Logger("play").debug(ex.toString) }
                    else Promise.pure(()))((_, e: r.BODY_CONTENT) => writer(e))

                  Enumeratee.breakE[r.BODY_CONTENT](_ => !e.getChannel.isConnected())(writeIteratee).mapDone { _ =>
                    if (e.getChannel.isConnected()) {
                      val f = e.getChannel.write(HttpChunk.LAST_CHUNK);
                      if (!keepAlive) f.addListener(ChannelFutureListener.CLOSE)
                    }
                  }
                }

                chunks(chunksIteratee)

              }

              case _ =>
                val channelBuffer = ChannelBuffers.dynamicBuffer(512)
                val nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(500))
                nettyResponse.setContent(channelBuffer)
                nettyResponse.setHeader(CONTENT_LENGTH, 0)
                val f = e.getChannel.write(nettyResponse)
                if (!keepAlive) f.addListener(ChannelFutureListener.CLOSE)
            }
          }
        }
        // get handler for request
        val handler = server.getHandlerFor(requestHeader)

        handler match {

          //execute normal action
          case Right((action: EssentialAction, app)) => {

            Logger("play").trace("Serving this request with: " + action)

            val eventuallyBodyParser = scala.concurrent.Future(action(requestHeader))

            val _ =
              eventuallyBodyParser.flatMap { bodyParser =>

                requestHeader.headers.get("Expect") match {
                  case Some("100-continue") => {
                    bodyParser.pureFold {
                      case Step.Done(_, _) => ()
                      case Step.Cont(k) => {
                        val continue = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)
                        //TODO wait for the promise of the write
                        e.getChannel.write(continue)

                      }
                      case Step.Error(_, _) => ()
                    }
                  }
                  case _ => Promise.pure()
                }
              }

            val eventuallyResultIteratee = if (nettyHttpRequest.isChunked) {

              val (result, handler) = newRequestBodyHandler(eventuallyBodyParser, allChannels, server)

              val p: ChannelPipeline = ctx.getChannel().getPipeline()
              p.replace("handler", "handler", handler)

              result

            } else {

              lazy val bodyEnumerator = {
                val body = {
                  val cBuffer = nettyHttpRequest.getContent()
                  val bytes = new Array[Byte](cBuffer.readableBytes())
                  cBuffer.readBytes(bytes)
                  bytes
                }
                Enumerator(body).andThen(Enumerator.enumInput(EOF))
              }

              eventuallyBodyParser.flatMap(it => bodyEnumerator |>> it): Promise[Iteratee[Array[Byte], Result]]

            }


            val eventuallyResult = eventuallyResultIteratee.flatMap(it => it.run)

            eventuallyResult.extend1 {
              case Redeemed(r) => 
                // Handle Flash Scope (probably not the good place to do it)
                val result = r match {
                  case r: PlainResult => {

                    val header = r.header

                    val flashCookie = {
                      header.headers.get(SET_COOKIE)
                      .map(Cookies.decode(_))
                      .flatMap(_.find(_.name == Flash.COOKIE_NAME)).orElse {
                        Option(requestHeader.flash).filterNot(_.isEmpty).map { _ =>
                           play.api.mvc.Cookie(Flash.COOKIE_NAME, "", 0)
                        }
                      }
                    }

                    flashCookie.map { newCookie =>
                      r.withHeaders(SET_COOKIE -> Cookies.merge(header.headers.get(SET_COOKIE).getOrElse(""), Seq(newCookie)))
                    }.getOrElse(r)

                  }
                  case r => r
                }
                response.handle(result)

              case Thrown(error) =>
                Logger("play").error("Cannot invoke the action, eventually got an error: " + error)
                response.handle( app.handleError(requestHeader, error))
                e.getChannel.setReadable(true)
            }

          }

          //execute websocket action
          case Right((ws @ WebSocket(f), app)) if (websocketableRequest.check) => {

            Logger("play").trace("Serving this request with: " + ws)

            try {
              val enumerator = websocketHandshake(ctx, nettyHttpRequest, e)(ws.frameFormatter)
              f(requestHeader)(enumerator, socketOut(ctx)(ws.frameFormatter))
            } catch {
              case e => e.printStackTrace
            }
          }

          //handle bad websocket request
          case Right((WebSocket(_), _)) => {

            Logger("play").trace("Bad websocket request")

            response.handle(Results.BadRequest)
          }

          //handle errors
          case Left(e) => {

            Logger("play").trace("No handler, got direct result: " + e)

            response.handle(e)
          }

        }

      case unexpected => Logger("play").error("Oops, unexpected message received in NettyServer (please report this problem): " + unexpected)

    }
  }

}
