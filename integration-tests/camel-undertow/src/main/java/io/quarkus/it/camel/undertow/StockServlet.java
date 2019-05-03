package io.quarkus.it.camel.undertow;

import java.io.IOException;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Basic test running JPA with the H2 database.
 * The application can work in either standard JVM or SubstrateVM, while we run H2 as a separate JVM process.
 */
@WebServlet(name = "StockServlet", urlPatterns = { "/stock-servlet", "/stock-servlet/sub-path" })
public class StockServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getWriter().write("GET " + req.getRequestURI());
    }

}
