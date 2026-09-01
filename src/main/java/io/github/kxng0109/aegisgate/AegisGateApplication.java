package io.github.kxng0109.aegisgate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableJpaRepositories(basePackages = "io.github.kxng0109.aegisgate.ledger")
public class AegisGateApplication {

	static void main(String[] args) {
		SpringApplication.run(AegisGateApplication.class, args);
	}

}
