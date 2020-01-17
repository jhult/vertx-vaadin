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

import com.github.mcollovati.vertx.Sync;
import com.github.mcollovati.vertx.http.HttpReverseProxy;
import com.github.mcollovati.vertx.support.StartupContext;
import com.github.mcollovati.vertx.vaadin.sockjs.communication.SockJSPushConnection;
import com.github.mcollovati.vertx.vaadin.sockjs.communication.SockJSPushHandler;
import com.github.mcollovati.vertx.web.sstore.ExtendedSessionStore;
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

import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

public class VertxVaadin {

    private static final Logger logger = LoggerFactory.getLogger(VertxVaadin.class);
    private static final String VAADIN_SESSION_EXPIRED_ADDRESS = "vaadin.session.expired";
    private static final String VERSION;

    private final VertxVaadinService service;
    private final VaadinOptions config;
    private final Vertx vertx;
    private final VertxVaadinOverrides overrides;
    private final Router router;
    private final ExtendedSessionStore sessionStore;

    static final String SLASH = "/";
    private static final String META_INF = "META-INF";
    static final String META_INF_RESOURCES = META_INF + SLASH + "resources";
    private static final String VAADIN = "VAADIN";
    private static final String VAADIN_STATIC = VAADIN + SLASH + "static";
    private static final String VAADIN_STATIC_CLIENT = VAADIN_STATIC + SLASH + "client";
    private static final String WEBROOT = "webroot";
    static final String WEBJAR = "webjar";
    private static final String WEBJARS = WEBJAR + "s";
    private static final String BUILD = "build";
    private static final String VAADIN_BUILD = VAADIN + SLASH + BUILD;
    private static final String SLASH_STAR = SLASH + "*";
    static final String FRONTEND = "frontend";
    private static final String FRONTEND_ES_6 = FRONTEND + "-es6";
    private static final String DYNAMIC = "dynamic";

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

    public VertxVaadin(final StartupContext startupContext, final ExtendedSessionStore sessionStore) {
        this(startupContext, sessionStore, null);
    }

    public VertxVaadin(final StartupContext startupContext, final VertxVaadinOverrides vertxVaadinOverrides) {
        this(startupContext, null, vertxVaadinOverrides);
    }

    public VertxVaadin(StartupContext startupContext) {
        this(startupContext, null, null);
    }

    public VertxVaadin(final StartupContext startupContext, final ExtendedSessionStore sessionStore, final VertxVaadinOverrides vertxVaadinOverrides) {
        Objects.requireNonNull(startupContext);
        this.vertx = Objects.requireNonNull(startupContext.vertx());
        this.config = startupContext.vaadinOptions();

        if (vertxVaadinOverrides == null) {
            overrides = new VertxVaadinOverrides.Default();
        } else {
            overrides = vertxVaadinOverrides;
        }

        service = overrides.createVaadinService(startupContext);

        logger.trace("Configuring SockJS Push connection");
        service.addUIInitListener(event ->
                event.getUI().getInternals().setPushConnection(new SockJSPushConnection(event.getUI()))
        );

        try {
            service.init();
        } catch (Exception ex) {
            throw new VertxException("Cannot initialize Vaadin service", ex);
        }

        if (sessionStore == null) {
            this.sessionStore = overrides.createSessionStore(vertx);
        } else {
            this.sessionStore = sessionStore;
        }

        addSessionExpirationHandler();
        configureSessionStore();

        router = initRouter();

        overrides.serviceInitialized();
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
        return (T) service;
    }

    protected final VaadinOptions config() {
        return config;
    }

    private Router initRouter() {
        logger.debug("Initializing router");
        String sessionCookieName = sessionCookieName();
        SessionHandler sessionHandler = SessionHandler.create(sessionStore)
            .setSessionTimeout(config().sessionTimeout())
            .setSessionCookieName(sessionCookieName)
            .setNagHttps(false)
            .setCookieHttpOnlyFlag(true);

        final Router vertxRouter = Router.router(vertx);
        // Redirect mountPoint to mountPoint/
        vertxRouter.routeWithRegex("^$").handler(ctx -> ctx.response()
            .putHeader(HttpHeaders.LOCATION, ctx.request().uri() + SLASH)
            .setStatusCode(302).end()
        );

        vertxRouter.route().handler(BodyHandler.create());

        // Disable SessionHandler for /VAADIN/ static resources
        vertxRouter.routeWithRegex("^(?!/(" + VAADIN + "(?!/" + DYNAMIC + ")|" + FRONTEND + "|" + FRONTEND_ES_6 + "|" + WEBJARS + "|" + WEBROOT + ")/).*$").handler(sessionHandler);

        // Forward vaadinPush javascript to sockjs implementation
        vertxRouter.routeWithRegex(SLASH + VAADIN_STATIC + "/push/vaadinPush(?<min>-min)?\\.js(?<compressed>\\.gz)?")
            .handler(ctx -> ctx.reroute(
                String.format("%s/%s/push/vaadinPushSockJS%s.js%s", ctx.mountPoint(),
                        VAADIN_STATIC,
                    Objects.toString(ctx.request().getParam("min"), ""),
                    Objects.toString(ctx.request().getParam("compressed"), "")
                )
            ));

        if (DevModeHandler.getDevModeHandler() != null) {
            logger.info("Starting DevModeHandler proxy");
            HttpReverseProxy proxy = HttpReverseProxy.create(vertx, DevModeHandler.getDevModeHandler().getPort());
            vertxRouter.routeWithRegex(".+\\.js$").blockingHandler(proxy::forward);
        }

        //vaadinRouter.route(SLASH + VAADIN + SLASH + DYNAMIC + SLASH_STAR).handler(this::handleVaadinRequest);
        final ClassLoader classLoader = getClass().getClassLoader();

        final StaticHandler webRoot = StaticHandler.create(WEBROOT, classLoader);

        vertxRouter.route(SLASH + VAADIN_STATIC_CLIENT + SLASH_STAR)
                .handler(createStaticHandlerForMetaInfResources(VAADIN_STATIC_CLIENT, classLoader));
        vertxRouter.route(SLASH + VAADIN_BUILD + SLASH_STAR)
                .handler(StaticHandler.create(META_INF + SLASH + VAADIN_BUILD, classLoader));
        vertxRouter.route(SLASH + VAADIN_STATIC + SLASH_STAR)
                .handler(StaticHandler.create(VAADIN_STATIC, classLoader))
                .handler(createStaticHandlerForMetaInfResources(VAADIN_STATIC, classLoader));
        vertxRouter.routeWithRegex(SLASH + VAADIN + "(?!/" + DYNAMIC + ")/.*")
                .handler(StaticHandler.create(VAADIN, classLoader));
        vertxRouter.route(SLASH + WEBROOT + SLASH_STAR)
                .handler(webRoot);
        vertxRouter.route(SLASH + WEBJARS + SLASH_STAR)
                .handler(webRoot)
                .handler(createStaticHandlerForMetaInfResources(WEBJARS, classLoader));
        vertxRouter.routeWithRegex(SLASH + FRONTEND + "/bower_components/(?<" + WEBJAR + ">.*)")
                .handler(ctx -> {
                    String rerouteTo = String.format("%s/%s/%s",
                            ctx.mountPoint(), WEBJARS, Objects.toString(ctx.request().getParam(WEBJAR), ""));
                    logger.trace("Rerouting bower component to {}", rerouteTo);
                    ctx.reroute(rerouteTo);
                }
            );

        logger.trace("Setup fronted routes");
        vertxRouter.route(SLASH + FRONTEND + SLASH_STAR)
                .handler(StaticHandler.create(FRONTEND, classLoader))
                .handler(webRoot)
                .handler(createStaticHandlerForMetaInfResources(FRONTEND, classLoader));
        vertxRouter.route(SLASH + FRONTEND_ES_6 + SLASH_STAR)
                .handler(StaticHandler.create(FRONTEND_ES_6, classLoader))
                .handler(createStaticHandlerForMetaInfResources(FRONTEND_ES_6, classLoader));

        initSockJS(vertxRouter, sessionHandler);

        vertxRouter.route(SLASH_STAR).handler(StaticHandler.create(META_INF_RESOURCES, classLoader));

        overrides.addAdditionalRoutes(vertxRouter, sessionHandler, service);

        vertxRouter.route(SLASH_STAR).handler(this::handleVaadinRequest);
        return vertxRouter;
    }

    private StaticHandler createStaticHandlerForMetaInfResources(final String endPath, final ClassLoader classLoader) {
        return StaticHandler.create(META_INF_RESOURCES + SLASH + endPath, classLoader);
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
        try {
            SockJSHandlerOptions options = new SockJSHandlerOptions()
                    .setSessionTimeout(config().sessionTimeout())
                    .setHeartbeatInterval(service.getDeploymentConfiguration().getHeartbeatInterval() * 1000);
            SockJSHandler sockJSHandler = SockJSHandler.create(vertx, options);
            SockJSPushHandler pushHandler = new SockJSPushHandler(service, sessionHandler, sockJSHandler);

            String pushPath = config.pushURL().replaceFirst("/$", "") + SLASH_STAR;
            logger.debug("Setup PUSH communication on {}", pushPath);
            vaadinRouter.route(pushPath).handler(rc -> {
                if (ApplicationConstants.REQUEST_TYPE_PUSH.equals(rc.request().getParam(ApplicationConstants.REQUEST_TYPE_PARAMETER))) {
                    pushHandler.handle(rc);
                } else {
                    rc.next();
                }
            });
        } catch (final NoClassDefFoundError e) {
            logger.info("SockJSHandler not found on class path; SockJS support is disabled", e);
        }
    }

    private String sessionCookieName() {
        return config().sessionCookieName();
    }

    private void addSessionExpirationHandler() {
        MessageProducer<String> sessionExpiredProducer = sessionExpiredProducer(service);
        sessionStore.expirationHandler(res -> {
            if (res.succeeded()) {
                sessionExpiredProducer.write(res.result());
            } else {
                logger.error(res.cause().getMessage(), res.cause());
            }
        });
    }

    public static VertxVaadin create(final Vertx vertx, final JsonObject config) {
        StartupContext startupContext = Sync.await(completer -> StartupContext.of(vertx, new VaadinOptions(config)).setHandler(completer));
        return new VertxVaadin(startupContext);
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
