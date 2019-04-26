package io.quarkus.it.camel.jdbc;

import javax.enterprise.context.ApplicationScoped;

import org.apache.camel.builder.RouteBuilder;

@ApplicationScoped
public class CamelRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("timer:jdbc?repeatCount=1")
                .setBody(constant("select * from camels"))
                .to("jdbc:camelsDs")
                .marshal().csv()
                .to("file:target?fileName=out.csv");
    }

}
