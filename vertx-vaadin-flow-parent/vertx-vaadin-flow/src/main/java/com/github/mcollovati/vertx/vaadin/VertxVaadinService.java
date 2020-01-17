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
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.mcollovati.vertx.support.BufferInputStreamAdapter;
import com.github.mcollovati.vertx.support.StartupContext;
import com.github.mcollovati.vertx.vaadin.communication.VertxFaviconHandler;
import com.github.mcollovati.vertx.vaadin.communication.VertxStreamRequestHandler;
import com.github.mcollovati.vertx.vaadin.communication.VertxWebComponentBootstrapHandler;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.BootstrapHandler;
import com.vaadin.flow.server.PwaRegistry;
import com.vaadin.flow.server.RequestHandler;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.server.ServiceException;
import com.vaadin.flow.server.ServletHelper;
import com.vaadin.flow.server.UnsupportedBrowserHandler;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServlet;
import com.vaadin.flow.server.VaadinServletContext;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WebBrowser;
import com.vaadin.flow.server.communication.HeartbeatHandler;
import com.vaadin.flow.server.communication.PwaHandler;
import com.vaadin.flow.server.communication.SessionRequestHandler;
import com.vaadin.flow.server.communication.StreamRequestHandler;
import com.vaadin.flow.server.communication.UidlRequestHandler;
import com.vaadin.flow.server.communication.WebComponentBootstrapHandler;
import com.vaadin.flow.server.communication.WebComponentProvider;
import com.vaadin.flow.server.startup.ApplicationRouteRegistry;
import com.vaadin.flow.server.webcomponent.WebComponentConfigurationRegistry;
import com.vaadin.flow.shared.ApplicationConstants;
import com.vaadin.flow.theme.AbstractTheme;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.impl.FileResolver;
import io.vertx.core.http.impl.MimeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.mcollovati.vertx.vaadin.VertxVaadin.META_INF_RESOURCES;
import static com.github.mcollovati.vertx.vaadin.VertxVaadin.SLASH;
import static com.github.mcollovati.vertx.vaadin.VertxVaadin.WEBJAR;
import static com.vaadin.flow.server.frontend.FrontendUtils.FRONTEND;

/**
 * Created by marco on 16/07/16.
 */
public class VertxVaadinService extends VaadinServletService {

    private static final Logger logger = LoggerFactory.getLogger(VertxVaadinService.class);

    private final transient StartupContext startupContext;
    private final transient DeploymentConfiguration deploymentConfiguration;
    private final transient WebJars webJars;

    protected VertxVaadinService(final StartupContext startupContext, final DeploymentConfiguration deploymentConfiguration) {
        this.startupContext = Objects.requireNonNull(startupContext);
        this.deploymentConfiguration =  Objects.requireNonNull(deploymentConfiguration);
        setDeploymentConfigurationAsClassLoader();

        logger.trace("Setup WebJar server");
        webJars = new WebJars(deploymentConfiguration);
    }

    public static VertxVaadinService create(final StartupContext startupContext) {
        Objects.requireNonNull(startupContext);
        DeploymentConfiguration deploymentConfiguration = DeploymentConfigurationFactory.createDeploymentConfiguration(VertxVaadinService.class, startupContext.vaadinOptions());
        return new VertxVaadinService(startupContext, deploymentConfiguration);
    }

    public void setDeploymentConfigurationAsClassLoader() {
        final String classLoaderName = getDeploymentConfiguration().getClassLoaderName();

        if (classLoaderName != null) {
            try {
                final Class<?> classLoaderClass = getClass().getClassLoader()
                        .loadClass(classLoaderName);
                final Constructor<?> c = classLoaderClass
                        .getConstructor(ClassLoader.class);
                setClassLoader((ClassLoader) c.newInstance(
                        new Object[] { getClass().getClassLoader() }));
            } catch (final Exception e) {
                throw new RuntimeException(
                        "Could not find specified class loader: "
                                + classLoaderName,
                        e);
            }
        }

        if (getClassLoader() == null) {
            setDefaultClassLoader();
        }
    }

    public Vertx getVertx() {
        return startupContext.vertx();
    }

    public VaadinServletContext getVaadinServletContext() {
        return startupContext.vaadinServletContext();
    }

    public ServletContext getServletContext() {
        return startupContext.servletContext();
    }

    @Override
    public DeploymentConfiguration getDeploymentConfiguration() {
        return deploymentConfiguration;
    }

    @Override
    public VaadinServlet getServlet() {
        // TODO - does this actually need something?
        return null;
    }

    @Override
    protected RouteRegistry getRouteRegistry() {
        return ApplicationRouteRegistry.getInstance(getServletContext());
    }

    @Override
    protected PwaRegistry getPwaRegistry() {
        return PwaRegistry.getInstance(getServletContext());
    }

    /**
     * Copied from {@link VaadinService#hasWebComponentConfigurations()}
     */
    private boolean hasWebComponentConfigurations() {
        WebComponentConfigurationRegistry registry = WebComponentConfigurationRegistry.getInstance(this.getContext());
        return registry.hasConfigurations();
    }

    @Override
    protected List<RequestHandler> createRequestHandlers() throws ServiceException {
        List<RequestHandler> handlers = new ArrayList<>();
        handlers.add(new SessionRequestHandler());
        handlers.add(new HeartbeatHandler());
        handlers.add(new UidlRequestHandler());
        handlers.add(new UnsupportedBrowserHandler());
        handlers.add(new StreamRequestHandler());
        PwaRegistry pwaRegistry = this.getPwaRegistry();
        if (pwaRegistry != null && pwaRegistry.getPwaConfiguration().isEnabled()) {
            handlers.add(new PwaHandler(pwaRegistry));
        }

        if (this.hasWebComponentConfigurations()) {
            handlers.add(new WebComponentProvider());
            handlers.add(new WebComponentBootstrapHandler());
        }

        handlers.add(new VertxFaviconHandler());

        handlers.replaceAll(this::replaceRequestHandlers);

        handlers.add(0, new BootstrapHandler());

        return handlers;
    }

    private RequestHandler replaceRequestHandlers(RequestHandler requestHandler) {
        if (requestHandler instanceof StreamRequestHandler) {
            return new VertxStreamRequestHandler();
        } else if (requestHandler instanceof WebComponentBootstrapHandler) {
            return new VertxWebComponentBootstrapHandler();
        }
        return requestHandler;
    }

    @Override
    public String getMimeType(String resourceName) {
        return MimeMapping.getMimeTypeForFilename(resourceName);
    }

    @Override
    public boolean ensurePushAvailable() {
        return true;
    }

    @Override
    public String getMainDivId(VaadinSession session, VaadinRequest request) {
        String appId;
        // Have to check due to VertxBootstrapHandler tricks
        if (request instanceof VertxVaadinRequest) {
            appId = ((VertxVaadinRequest) request).getRoutingContext().mountPoint();
        } else {
            appId = request.getContextPath();
        }

        if (appId == null || "".equals(appId) || "/".equals(appId)) {
            appId = "ROOT";
        }
        appId = appId.replaceAll("[^a-zA-Z0-9]", "");
        // Add hashCode to the end, so that it is still (sort of)
        // predictable, but indicates that it should not be used in CSS
        // and
        // such:
        int hashCode = appId.hashCode();
        if (hashCode < 0) {
            hashCode = -hashCode;
        }
        appId = appId + "-" + hashCode;
        return appId;
    }

    @Override
    public String getServiceName() {
        return startupContext.vaadinOptions().serviceName().orElseGet(() -> getClass().getName() + ".service");
    }

    @Override
    public URL getStaticResource(String url) {
        return tryResolveFile(url);
    }

    @Override
    public URL getResource(String url, WebBrowser browser, AbstractTheme theme) {
        logger.info("url: {}, browser: {}, theme: {}", url, browser, theme);
        return tryGetResource(getThemedOrRawPath(url, browser, theme));
    }

    @Override
    public InputStream getResourceAsStream(String url, WebBrowser browser, AbstractTheme theme) {
        return tryGetResourceAsStream(getThemedOrRawPath(url, browser, theme));
    }

    /**
     * Needs to stay so we call {@link VertxVaadinService#getThemedOrRawPath}
     */
    @Override
    public Optional<String> getThemedUrl(String url, WebBrowser browser, AbstractTheme theme) {
        if (theme != null
            && !resolveResource(url, browser).equals(getThemedOrRawPath(url, browser, theme))) {
            return Optional.of(theme.translateUrl(url));
        }
        return Optional.empty();
    }

    @Override
    protected VaadinContext constructVaadinContext() {
        return new VertxVaadinContext(getVertx());
    }

    /**
     * Resolves the given {@code url} resource and tries to find a themed or raw
     * version.
     * <p>
     * The themed version is always tried first, with the raw version used as a
     * fallback.
     *
     * @param url
     *            the untranslated URL to the resource to find
     * @param browser
     *            the web browser to resolve for, relevant for es5 vs es6
     *            resolving
     * @param theme
     *            the theme to use for resolving, or <code>null</code> to not
     *            use a theme
     * @return the path to the themed resource if such exists, otherwise the
     *         resolved raw path
     */
    private String getThemedOrRawPath(String url, WebBrowser browser,
                                       AbstractTheme theme) {
        String resourcePath = resolveResource(url, browser);

        Optional<String> themeResourcePath = getThemeResourcePath(resourcePath, theme);
        if (themeResourcePath.isPresent()) {
            URL themeResource = tryGetResource(
                    themeResourcePath.get());
            if (themeResource != null) {
                return themeResourcePath.get();
            }
        }
        return resourcePath;
    }

    private URL tryGetResource(String path) {
        logger.trace("Try to resolve path {}", path);
        URL url = tryResolveFile(path);
        if (url == null && !path.startsWith("META-INF/resources/")) {
            logger.trace("Path {} not found, try into /META-INF/resources/", path);
            url = tryResolveFile("/META-INF/resources/" + path);
        }
        if (url == null) {
            logger.trace("Path {} not found into META-INF/resources/, try with webjars", path);
            url = webJars.getWebJarResourcePath(path)
                .map(this::tryResolveFile).orElse(null);
        }
        return url;
    }

    private URL tryResolveFile(String path) {
        FileSystem fileSystem = getVertx().fileSystem();
        String relativePath = makePathRelative(path);
        if (fileSystem.existsBlocking(relativePath)) {
            try {
                return new FileResolver()
                    .resolveFile(relativePath).toURI().toURL();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * Gets the theme specific path for the given resource.
     *
     * @param path
     *            the raw path
     * @param theme
     *            the theme to use for resolving, possibly <code>null</code>
     * @return the path to the themed version or an empty optional if no themed
     *         version could be determined
     */
    private Optional<String> getThemeResourcePath(String path,
                                                  AbstractTheme theme) {
        if (theme == null) {
            return Optional.empty();
        }
        String themeUrl = theme.translateUrl(path);
        if (path.equals(themeUrl)) {
            return Optional.empty();
        }

        return Optional.of(themeUrl);
    }

    public InputStream tryGetResourceAsStream(String path) {
        logger.trace("Try to resolve path {}", path);
        String relativePath = makePathRelative(path);
        FileSystem fileSystem = getVertx().fileSystem();
        if (fileSystem.existsBlocking(relativePath)) {
            return new BufferInputStreamAdapter(fileSystem.readFileBlocking(relativePath));
        }
        if (!path.startsWith("/META-INF/resources")) {
            logger.trace("Path {} not found, try into /META-INF/resources/", path);
            InputStream is = tryGetResourceAsStream("/META-INF/resources/" + relativePath);
            if (is != null) {
                return is;
            }
        }
        logger.trace("Path {} not found into META-INF/resources/, try with webjars", path);
        return webJars.getWebJarResourcePath(path)
            .filter(fileSystem::existsBlocking)
            .map(fileSystem::readFileBlocking)
            .map(BufferInputStreamAdapter::new)
            .orElse(null);
    }

    private String makePathRelative(String path) {
        String relativePath = path;
        if (path.startsWith("/")) {
            relativePath = relativePath.substring(1);
            //relativePath = "." + relativePath;
        }
        return relativePath;
    }

    /**
     * Gets a relative path you can use to refer to the context root.
     *
     * @param request the request for which the location should be determined
     * @return A relative path to the context root. Never ends with a slash (/).
     */
    @Override
    public String getContextRootRelativePath(VaadinRequest request) {
        return getCancelingRelativePath("/") + "/";
    }

    // Just to avoid direct calls to VaadinServletService
    // from outside VertxVaadinService
    public static String getCancelingRelativePath(String servletPath) {
        return ServletHelper.getCancelingRelativePath(servletPath);
    }

    private static final class WebJars {

        static final String WEBJARS_RESOURCES_PREFIX = META_INF_RESOURCES + "/webjars/";
        private final Pattern urlPattern;

        private WebJars(DeploymentConfiguration deploymentConfiguration) {
            String frontendPrefix = deploymentConfiguration
                    .getNpmFrontendPrefix();
            if (!frontendPrefix.endsWith(SLASH)) {
                throw new IllegalArgumentException(
                        "Frontend prefix must end with a /. Got \"" + frontendPrefix
                                + "\"");
            }
            if (!frontendPrefix
                    .startsWith(ApplicationConstants.CONTEXT_PROTOCOL_PREFIX)) {
                throw new IllegalArgumentException(
                        "Cannot host WebJars for a fronted prefix that isn't relative to 'context://'. Current " + FRONTEND + " prefix: "
                                + frontendPrefix);
            }

            String webjarsLocation = SLASH
                    + frontendPrefix.substring(
                    ApplicationConstants.CONTEXT_PROTOCOL_PREFIX.length());

            urlPattern = Pattern.compile("^((/\\.)?(/\\.\\.)*)" + webjarsLocation + "(bower_components/)?(?<" + WEBJAR + ">.*)");
        }

        /**
         * Gets web jar resource path if it exists.
         *
         * @param filePathInContext servlet context path for file
         * @return an optional web jar resource path, or an empty optional if the
         * resource is not web jar resource
         */
        public Optional<String> getWebJarResourcePath(String filePathInContext) {
            String webJarPath = null;

            Matcher matcher = urlPattern.matcher(filePathInContext);
            // If we don't find anything then we don't have the prefix at all.
            if (matcher.find()) {
                webJarPath = WEBJARS_RESOURCES_PREFIX + matcher.group(WEBJAR);
            }
            return Optional.ofNullable(webJarPath);
        }
    }
}
