package systems.grebe.devtools.mcp;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Einstieg beim Deployment als WAR in einen externen Servlet-Container (WildFly, {@code ./gradlew :server:war}).
 * Port und Bind-Adresse bestimmt der Container.
 */
public class WildFlyInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(DevToolsServerApplication.class);
    }
}
