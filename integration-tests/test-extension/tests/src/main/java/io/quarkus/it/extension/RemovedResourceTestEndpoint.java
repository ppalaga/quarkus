package io.quarkus.it.extension;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.quarkus.extest.runtime.RemovedResource;
import io.quarkus.extest.runtime.RemovedResource.ClassLoaderKind;

@WebServlet(name = "RemovedResourceTestEndpoint", urlPatterns = "/core/removed-resource")
public class RemovedResourceTestEndpoint extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            RemovedResource resource = RemovedResource.valueOf(req.getParameter("resource"));
            ClassLoaderKind clKind = ClassLoaderKind.valueOf(req.getParameter("classLoaderKind"));
            resp.getWriter().write(resource.load(clKind));
        } catch (Exception | AssertionError e) {
            reportThrowable(e, resp);
        }
    }

    private void reportThrowable(final Throwable t, final HttpServletResponse resp) throws IOException {
        reportThrowable(null, t, resp);
    }

    private void reportThrowable(String errorMessage, final Throwable t, final HttpServletResponse resp) throws IOException {
        final PrintWriter writer = resp.getWriter();
        if (errorMessage != null) {
            writer.write(errorMessage);
            writer.write(" ");
        }
        t.printStackTrace(writer);
        writer.append("\n\t");
    }

}
