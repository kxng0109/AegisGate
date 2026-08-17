package io.github.kxng0109.aegisgate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AegisGateApplication {

	public static void main(String[] args) {
		SpringApplication.run(AegisGateApplication.class, args);
	}

}
