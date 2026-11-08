package gregochr.cinefilm.config;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the single-page app from {@code classpath:/static/}: real files (hashed assets, favicon) as they are, and
 * {@code index.html} for client routes such as {@code /films/12} or {@code /library}. It never answers for
 * {@code /api/**} or {@code /actuator/**}: an unknown path there is a plain 404, not the app shell.
 */
@Configuration
public class SpaFallbackConfig implements WebMvcConfigurer {

    private static final String INDEX = "index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
            .addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .setCacheControl(CacheControl.noCache())
            .resourceChain(true)
            .addResolver(new SpaResolver());
    }

    /**
     * Whether a request path (no leading slash) is a client route: not an API or actuator path and not a file name
     * with an extension (a missing {@code .js} must be a 404, not HTML).
     *
     * @param path the resource path relative to the context root
     * @return true when the app shell should answer it
     */
    static boolean isClientRoute(String path) {
        if (path.equals("api") || path.startsWith("api/") || path.equals("actuator") || path.startsWith("actuator/")) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return !last.contains(".");
    }

    /** Returns the requested file when it exists, else {@code index.html} for a client route, else nothing. */
    private static final class SpaResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource file = super.getResource(resourcePath, location);
            if (file != null) {
                return file;
            }
            return isClientRoute(resourcePath) ? super.getResource(INDEX, location) : null;
        }
    }
}
