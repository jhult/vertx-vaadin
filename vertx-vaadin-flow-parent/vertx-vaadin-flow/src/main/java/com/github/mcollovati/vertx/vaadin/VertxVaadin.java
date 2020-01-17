/*
 * The MIT License
 * Copyright © 2016-2019 Marco Collovati (mcollovati@gmail.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.github.mcollovati.vertx.vaadin;

import javax.servlet.ServletContext;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import com.github.mcollovati.vertx.Sync;
import com.github.mcollovati.vertx.http.HttpReverseProxy;
import com.github.mcollovati.vertx.support.StartupContext;
import com.github.mcollovati.vertx.vaadin.sockjs.communication.SockJSPushConnection;
import com.github.mcollovati.vertx.vaadin.sockjs.communication.SockJSPushHandler;
import com.github.mcollovati.vertx.web.sstore.ExtendedLocalSessionStore;
import com.github.mcollovati.vertx.web.sstore.ExtendedSessionStore;
import com.github.mcollovati.vertx.web.sstore.NearCacheSessionStore;
import com.vaadin.flow.server.DevModeHandler;
import com.vaadin.flow.server.ServiceException;
import com.vaadin.flow.server.WrappedSession;
import com.vaadin.flow.shared.ApplicationConstants;
import com.vaadin.flow.shared.Registration;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.eventbus.MessageProducer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VertxVaadin {

    private static final Logger logger = LoggerFactory.getLogger(VertxVaadin.class);
    private static final String VAADIN_SESSION_EXPIRED_ADDRESS = "vaadin.session.expired";
    private static final String VERSION;

    private final VertxVaadinService service;
    private final StartupContext startupContext;
    private final VaadinOptions config;
    private final Vertx vertx;
    private final Router router;
    private final ExtendedSessionStore sessionStore;

    static final String SLASH = "/";
    static final String META_INF_RESOURCES = META_INF + SLASH + "resources";
    static final String WEBJAR = "webjar";
    static final String FRONTEND = "frontend";
    static {
        String version = "0.0.0";
        Properties properties = new Properties();
        try {
            properties.load(VertxVaadin.class.getResourceAsStream("version.properties"));
            version = properties.getProperty("vertx-vaadin.version");
        } catch (Exception e) {
            logger.warn("Unable to determine VertxVaadin version");
        }
        VERSION = version;
    }


    private VertxVaadin(Vertx vertx, Optional<ExtendedSessionStore> sessionStore, StartupContext startupContext) {
        this.vertx = Objects.requireNonNull(vertx);
        this.startupContext = Objects.requireNonNull(startupContext);
        this.config = startupContext.vaadinOptions();

        this.service = createVaadinService();

        logger.trace("Configuring SockJS Push connection");
        this.service.addUIInitListener(event ->
            event.getUI().getInternals().setPushConnection(new SockJSPushConnection(event.getUI()))
        );

        try {
            service.init();
        } catch (Exception ex) {
            throw new VertxException("Cannot initialize Vaadin service", ex);
        }

        this.sessionStore = withSessionExpirationHandler(
            this.service, sessionStore.orElseGet(this::createSessionStore)
        );
        configureSessionStore();
        this.router = initRouter();
    }

    protected VertxVaadin(Vertx vertx, ExtendedSessionStore sessionStore, StartupContext startupContext) {
        this(vertx, Optional.of(sessionStore), startupContext);
    }

    protected VertxVaadin(Vertx vertx, StartupContext startupContext) {
        this(vertx, Optional.empty(), startupContext);
    }

    private void configureSessionStore() {
        final Registration sessionInitListenerReg = service.addSessionInitListener(event -> {
            MessageConsumer<String> consumer = sessionExpiredHandler(vertx, msg ->
                Optional.of(event.getSession().getSession())
                    .filter(session -> msg.body().equals(session.getId()))
                    .ifPresent(WrappedSession::invalidate));
            AtomicReference<Registration> sessionDestroyListenerUnregister = new AtomicReference<>();
            sessionDestroyListenerUnregister.set(
                event.getService().addSessionDestroyListener(ev2 -> {
                    consumer.unregister();
                    sessionDestroyListenerUnregister.get().remove();
                })
            );

        });
        service.addServiceDestroyListener(event -> sessionInitListenerReg.remove());
    }

    public Router router() {
        return router;
    }

    public final Vertx vertx() {
        return vertx;
    }

    @SuppressWarnings("unchecked")
    public final <T extends VertxVaadinService> T vaadinService() {
    }
    public String serviceName() {
        return config.serviceName().orElseGet(() -> getClass().getName() + ".service");
    }


    protected final VaadinOptions config() {
        return config;
    }

    ServletContext servletContext() {
        return startupContext.servletContext();
    }

    protected void serviceInitialized(Router router) {
        // empty by default
    }

    protected VertxVaadinService createVaadinService() {
        VertxVaadinService service = new VertxVaadinService(this, createDeploymentConfiguration());
        return service;
    }

    protected ExtendedSessionStore createSessionStore() {
        if (vertx.isClustered()) {
            return NearCacheSessionStore.create(vertx);
        }
        return ExtendedLocalSessionStore.create(vertx);
    }

    private Router initRouter() {
        logger.debug("Initializing router");
        String sessionCookieName = sessionCookieName();
        SessionHandler sessionHandler = SessionHandler.create(sessionStore)
            .setSessionTimeout(config().sessionTimeout())
            .setSessionCookieName(sessionCookieName)
            .setNagHttps(false)
            .setCookieHttpOnlyFlag(true);

        Router vaadinRouter = Router.router(vertx);
        // Redirect mountPoint to mountPoint/
        vaadinRouter.routeWithRegex("^$").handler(ctx -> ctx.response()
            .putHeader(HttpHeaders.LOCATION, ctx.request().uri() + "/")
            .setStatusCode(302).end()
        );

        vaadinRouter.route().handler(BodyHandler.create());

        // Disable SessionHandler for /VAADIN/ static resources
        vaadinRouter.routeWithRegex("^(?!/(VAADIN(?!/dynamic)|frontend|frontend-es6|webjars|webroot)/).*$").handler(sessionHandler);

        // Forward vaadinPush javascript to sockjs implementation
        vaadinRouter.routeWithRegex("/VAADIN/static/push/vaadinPush(?<min>-min)?\\.js(?<compressed>\\.gz)?")
            .handler(ctx -> ctx.reroute(
                String.format("%s/VAADIN/static/push/vaadinPushSockJS%s.js%s", ctx.mountPoint(),
                    Objects.toString(ctx.request().getParam("min"), ""),
                    Objects.toString(ctx.request().getParam("compressed"), "")
                )
            ));


        if (DevModeHandler.getDevModeHandler() != null) {
            logger.info("Starting DevModeHandler proxy");
            HttpReverseProxy proxy = HttpReverseProxy.create(vertx, DevModeHandler.getDevModeHandler().getPort());
            vaadinRouter.routeWithRegex(".+\\.js$").blockingHandler(proxy::forward);
        }

        //
        //vaadinRouter.route("/VAADIN/dynamic/*").handler(this::handleVaadinRequest);
        vaadinRouter.route("/VAADIN/static/client/*")
            .handler(StaticHandler.create("META-INF/resources/VAADIN/static/client", getClass().getClassLoader()));
        vaadinRouter.route("/VAADIN/build/*").handler(StaticHandler.create("META-INF/VAADIN/build", getClass().getClassLoader()));
        vaadinRouter.route("/VAADIN/static/*").handler(StaticHandler.create("VAADIN/static", getClass().getClassLoader()));
        vaadinRouter.route("/VAADIN/static/*").handler(StaticHandler.create("META-INF/resources/VAADIN/static", getClass().getClassLoader()));
        vaadinRouter.routeWithRegex("/VAADIN(?!/dynamic)/.*").handler(StaticHandler.create("VAADIN", getClass().getClassLoader()));
        vaadinRouter.route("/webroot/*").handler(StaticHandler.create("webroot", getClass().getClassLoader()));
        vaadinRouter.route("/webjars/*").handler(StaticHandler.create("webroot", getClass().getClassLoader()));
        vaadinRouter.route("/webjars/*").handler(StaticHandler.create("META-INF/resources/webjars", getClass().getClassLoader()));
        vaadinRouter.routeWithRegex("/frontend/bower_components/(?<webjar>.*)").handler(ctx -> {
                logger.trace("Rerouting bower component to {}/webjars/{}",
                    ctx.mountPoint(), Objects.toString(ctx.request().getParam("webjar"), "")
                );
                ctx.reroute(String.format("%s/webjars/%s",
                    ctx.mountPoint(), Objects.toString(ctx.request().getParam("webjar"), "")
                ));
            }
        );

        logger.trace("Setup fronted routes");
        vaadinRouter.route("/frontend/*").handler(StaticHandler.create("frontend", getClass().getClassLoader()));
        vaadinRouter.route("/frontend/*").handler(StaticHandler.create("webroot", getClass().getClassLoader()));
        vaadinRouter.route("/frontend/*").handler(StaticHandler.create("META-INF/resources/frontend", getClass().getClassLoader()));
        vaadinRouter.route("/frontend-es6/*").handler(StaticHandler.create("frontend-es6", getClass().getClassLoader()));
        vaadinRouter.route("/frontend-es6/*").handler(StaticHandler.create("META-INF/resources/frontend-es6", getClass().getClassLoader()));

        initSockJS(vaadinRouter, sessionHandler);

        vaadinRouter.route("/*").handler(StaticHandler.create("META-INF/resources", getClass().getClassLoader()));
        vaadinRouter.route("/*").handler(this::handleVaadinRequest);

        serviceInitialized(vaadinRouter);
        return vaadinRouter;
    }

    private void handleVaadinRequest(final RoutingContext routingContext) {
        VertxVaadinRequest request = new VertxVaadinRequest(service, routingContext);
        VertxVaadinResponse response = new VertxVaadinResponse(service, routingContext);

        try {
            logger.trace("Handling Vaadin request: {}", routingContext.request().uri());
            service.handleRequest(request, response);
            response.end();
        } catch (ServiceException ex) {
            logger.error("Error processing request {}" + routingContext.request().uri(), ex);
            routingContext.fail(ex);
        }
    }

    private void initSockJS(final Router vaadinRouter, final SessionHandler sessionHandler) {
        SockJSHandlerOptions options = new SockJSHandlerOptions()
            .setSessionTimeout(config().sessionTimeout())
            .setHeartbeatInterval(service.getDeploymentConfiguration().getHeartbeatInterval() * 1000);
        SockJSHandler sockJSHandler = SockJSHandler.create(vertx, options);
        SockJSPushHandler pushHandler = new SockJSPushHandler(service, sessionHandler, sockJSHandler);

        String pushPath = config.pushURL().replaceFirst("/$", "") + "/*";
        logger.debug("Setup PUSH communication on {}", pushPath);
        vaadinRouter.route(pushPath).handler(rc -> {
            if (ApplicationConstants.REQUEST_TYPE_PUSH.equals(rc.request().getParam(ApplicationConstants.REQUEST_TYPE_PARAMETER))) {
                pushHandler.handle(rc);
            } else {
                rc.next();
            }
        });
    }


    private String sessionCookieName() {
        return config().sessionCookieName();
    }

    private DeploymentConfiguration createDeploymentConfiguration() {
        return DeploymentConfigurationFactory.createDeploymentConfiguration(getClass(), config());
    }


    public static VertxVaadin create(Vertx vertx, ExtendedSessionStore sessionStore, StartupContext startupContext) {
        return new VertxVaadin(vertx, sessionStore, startupContext);
    }

    public static VertxVaadin create(Vertx vertx, JsonObject config) {
        StartupContext startupContext = Sync.await(completer -> StartupContext.of(vertx, new VaadinOptions(config)).setHandler(completer));
        return create(vertx, startupContext);
    }

    public static VertxVaadin create(Vertx vertx, StartupContext config) {
        return new VertxVaadin(vertx, config);
    }

    private static ExtendedSessionStore withSessionExpirationHandler(
        VertxVaadinService service, ExtendedSessionStore store
    ) {
        MessageProducer<String> sessionExpiredProducer = sessionExpiredProducer(service);
        store.expirationHandler(res -> {
            if (res.succeeded()) {
                sessionExpiredProducer.send(res.result());
            } else {
                res.cause().printStackTrace();
            }
        });
        return store;
    }

    private static MessageProducer<String> sessionExpiredProducer(final VertxVaadinService service) {
        return service.getVertx().eventBus().sender(VAADIN_SESSION_EXPIRED_ADDRESS);
    }

    public static MessageConsumer<String> sessionExpiredHandler(final Vertx vertx, final Handler<Message<String>> handler) {
        return vertx.eventBus().consumer(VAADIN_SESSION_EXPIRED_ADDRESS, handler);
    }

    public static String getVersion() {
        return VERSION;
    }
    }
