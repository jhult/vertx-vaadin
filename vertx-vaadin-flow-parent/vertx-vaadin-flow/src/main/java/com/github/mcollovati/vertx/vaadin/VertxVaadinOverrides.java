package com.github.mcollovati.vertx.vaadin;

import com.github.mcollovati.vertx.support.StartupContext;
import com.github.mcollovati.vertx.web.sstore.ExtendedLocalSessionStore;
import com.github.mcollovati.vertx.web.sstore.ExtendedSessionStore;
import com.github.mcollovati.vertx.web.sstore.NearCacheSessionStore;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.SessionHandler;

/**
 * @author Jonathan HUlt
 * All of these methods are (eventually) called from within the {@link VertxVaadin#VertxVaadin(StartupContext, ExtendedSessionStore, VertxVaadinOverrides)} constructor.
 */
public interface VertxVaadinOverrides {

    /**
     * Called early on in the {@link VertxVaadin} constructor.
     * Once the {@link VertxVaadinService} is initialized, the value will be stored in {@link VertxVaadin#service} which is accessible using {@link VertxVaadin#vaadinService}.
     * @see VertxVaadin#vaadinService
     *
     * @param startupContext {@link StartupContext} provided by {@link VaadinVerticle}.
     */
    default VertxVaadinService createVaadinService(final StartupContext startupContext) {
        return new VertxVaadinService(startupContext, DeploymentConfigurationFactory.createDeploymentConfiguration(VertxVaadin.class, startupContext.vaadinOptions()));
    }

    /**
     * Called after {@link VertxVaadinService} has been fully initialized but only if no {@link io.vertx.ext.web.sstore.SessionStore} was passed to the {@link VertxVaadin} constructor.
     * @param vertx {@link Vertx} provided by {@link VaadinVerticle}.
     */
    default ExtendedSessionStore createSessionStore(final Vertx vertx) {
        if (vertx.isClustered()) {
            return NearCacheSessionStore.create(vertx);
        }
        return ExtendedLocalSessionStore.create(vertx);
    }

    /**
     * Called during the end part of {@link VertxVaadin#initRouter} just before calling {@link VertxVaadin#initRouter}.
     * @param vertxRouter {@link Router}
     * @param sessionHandler {@link SessionHandler}
     * @param service {@link VertxVaadinService} created from {@link #createVaadinService} which is called in the {@link VertxVaadin} constructor.
     */
    default void addAdditionalRoutes(final Router vertxRouter, final SessionHandler sessionHandler, final VertxVaadinService service) {}

    /**
     * Called at end of the {@link VertxVaadin} constructor.
     */
    default void serviceInitialized() {}

    /**
     * Default implementation for those that don't need to override anything.
     */
    final class Default implements VertxVaadinOverrides{};
}