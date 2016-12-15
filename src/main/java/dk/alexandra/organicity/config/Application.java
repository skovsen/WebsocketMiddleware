package dk.alexandra.organicity.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "dk.alexandra.organicity")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        
    }
}
