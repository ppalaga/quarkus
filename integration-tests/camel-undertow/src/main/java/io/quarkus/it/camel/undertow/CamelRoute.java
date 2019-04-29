package io.quarkus.it.camel.undertow;

import org.apache.camel.builder.RouteBuilder;

public class CamelRoute extends RouteBuilder {

    @Override
    public void configure() {
        //from("timer:foo?repeatCount=1")
        from("undertow:http://localhost/hello")
                .setBody(simple("Hello"));

    }

}
