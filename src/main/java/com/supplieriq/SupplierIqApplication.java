package com.supplieriq;

import com.supplieriq.generator.DataGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class SupplierIqApplication {
    public static void main(String[] args) { SpringApplication.run(SupplierIqApplication.class, args); }

    @Bean
    @Order(1)
    CommandLineRunner ensureData() {
        return args -> {
            if (!Files.exists(Path.of("data", "suppliers.csv"))) {
                DataGenerator.generate("starter", 20261022L);
            }
        };
    }
}
