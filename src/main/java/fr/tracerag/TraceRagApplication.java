package fr.tracerag;

import fr.tracerag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class TraceRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(TraceRagApplication.class, args);
    }
}

