package com.stup.wristbandprinter.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Adds a plain-HTTP connector alongside the main connector so in-network workers can reach
 * management's internal endpoints (self-registration + heartbeat) without TLS.
 *
 * <p>In prod the main connector is HTTPS-only on 8443 with a self-signed certificate, which the
 * worker's {@code RestClient} cannot verify (PKIX path building failure). Rather than distribute
 * the cert to every worker, management also listens on a plain-HTTP port that is published only on
 * the private Docker network (never to the host), and workers register over
 * {@code http://management:<port>}. Public traffic stays HTTPS-only.
 *
 * <p>Enabled via {@code server.internal-http.enabled=true} (set in {@code application-prod.yml});
 * absent elsewhere, so local/dev management is unaffected. Management-only ({@code !worker}).
 */
@Configuration
@Profile("!worker")
@ConditionalOnProperty(name = "server.internal-http.enabled", havingValue = "true")
public class InternalHttpConnectorConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalHttpConnector(
            @Value("${server.internal-http.port:8081}") int port) {
        return factory -> {
            Connector connector = new Connector();
            connector.setScheme("http");
            connector.setSecure(false);
            connector.setPort(port);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
