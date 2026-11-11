package com.expplatform;

import com.expplatform.repository.Db;
import com.expplatform.repository.SchemaInitializer;
import com.expplatform.service.ExportService;
import com.expplatform.service.GenerationService;
import com.expplatform.service.ReproducibilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class ExperimentPlatformApplication {

    private static final Logger log = LoggerFactory.getLogger(ExperimentPlatformApplication.class);

    public static void main(String[] args) {
        // Let the MySQL driver bind NULLs without asking for parameter metadata (faster batch inserts)
        System.setProperty("spring.jdbc.getParameterType.ignore", "true");
        SpringApplication.run(ExperimentPlatformApplication.class, args);
    }

    @Bean
    WebMvcConfigurer cors(@Value("${app.cors-origins}") String[] origins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
            }
        };
    }

    /**
     * Startup / CLI:
     *   --generate   regenerate the dataset with app.seed / app.users / app.experiments
     *   --export     write CSV/JSON/SQL + manifest to app.export-dir
     *   --repro      regenerate twice and compare checksums
     *   --exit       stop after the above (batch mode)
     * Without flags the dataset is generated once if the database is empty (app.generate-if-empty).
     */
    @Bean
    ApplicationRunner startup(SchemaInitializer schema, Db db, GenerationService generation, ExportService export,
                              ReproducibilityService repro, ConfigurableApplicationContext ctx,
                              @Value("${app.seed}") long seed, @Value("${app.users}") int users,
                              @Value("${app.experiments}") int experiments,
                              @Value("${app.generate-if-empty:true}") boolean generateIfEmpty) {
        return (ApplicationArguments args) -> {
            schema.initialize();
            boolean empty = db.count("SELECT COUNT(*) FROM experiment") == 0;
            if (args.containsOption("repro")) {
                var report = repro.check(seed, users, experiments);
                log.info("Reproducibility: reproducible={} report={}", report.get("reproducible"), report.get("report_file"));
            } else if (args.containsOption("generate") || (empty && generateIfEmpty)) {
                log.info("Generating dataset seed={} users={} experiments={}", seed, users, experiments);
                var r = generation.generate(seed, users, experiments, "cli", true);
                log.info("Generation finished: {}", r);
            }
            if (args.containsOption("export")) {
                var r = export.export();
                log.info("Exported to {}", r.get("export_dir"));
            }
            if (args.containsOption("exit")) {
                System.exit(SpringApplication.exit(ctx, () -> 0));
            }
        };
    }
}
