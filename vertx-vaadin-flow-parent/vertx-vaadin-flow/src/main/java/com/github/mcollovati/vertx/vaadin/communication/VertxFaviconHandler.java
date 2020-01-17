package com.github.mcollovati.vertx.vaadin.communication;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.FaviconHandler;

import java.io.IOException;

public class VertxFaviconHandler extends FaviconHandler {

    @Override
    public boolean handleRequest(VaadinSession session, VaadinRequest request, VaadinResponse response) throws IOException {
        boolean isFavicon = request.getContextPath().isEmpty() && "/favicon.ico".equals(request.getPathInfo());
        if (isFavicon) {
            response.setStatus(404);
        }

        return isFavicon;
    }
}
