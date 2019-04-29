package io.quarkus.camel.component.undertow.runtime;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.camel.component.undertow.HttpHandlerRegistrationInfo;
import org.apache.camel.component.undertow.UndertowHost;
import org.apache.camel.component.undertow.handlers.CamelRootHandler;

import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;

public class CamelUndertowHandlerWrapper implements HandlerWrapper, UndertowHost {
    private CamelRootHandler rootHandler;

    private final Map<HttpHandlerRegistrationInfo, HttpHandler> pendingRegistrations = new LinkedHashMap<>();

    @Override
    public HttpHandler wrap(HttpHandler handler) {
        synchronized (pendingRegistrations) {
            this.rootHandler = new CamelRootHandler(handler);
            for (Entry<HttpHandlerRegistrationInfo, HttpHandler> e : pendingRegistrations.entrySet()) {
                final HttpHandlerRegistrationInfo registrationInfo = e.getKey();
                rootHandler.add(registrationInfo.getUri().getPath(),
                        registrationInfo.getMethodRestrict(), registrationInfo.isMatchOnUriPrefix(), e.getValue());
            }
            pendingRegistrations.clear();
        }
        return this.rootHandler;
    }

    @Override
    public void validateEndpointURI(URI httpURI) {
        // all URIs are good
    }

    @Override
    public HttpHandler registerHandler(HttpHandlerRegistrationInfo registrationInfo, HttpHandler handler) {
        synchronized (pendingRegistrations) {
            if (rootHandler == null) {
                pendingRegistrations.put(registrationInfo, handler);
                return handler;
            } else {
                return rootHandler.add(registrationInfo.getUri().getPath(),
                        registrationInfo.getMethodRestrict(), registrationInfo.isMatchOnUriPrefix(), handler);
            }
        }
    }

    @Override
    public void unregisterHandler(HttpHandlerRegistrationInfo registrationInfo) {
        synchronized (pendingRegistrations) {
            if (rootHandler == null) {
                pendingRegistrations.remove(registrationInfo);
            } else {
                rootHandler.remove(registrationInfo.getUri().getPath(), registrationInfo.getMethodRestrict(),
                        registrationInfo.isMatchOnUriPrefix());
            }
        }
    }

}
