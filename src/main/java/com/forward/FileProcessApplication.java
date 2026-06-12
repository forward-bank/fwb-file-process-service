package com.forward;

import com.forward.mq.MQConfig;
import com.forward.mq.listener.FileProcessRequestListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class FileProcessApplication {

    public static void main(String[] args) {
        System.out.println("╔═════════════════════════════════════════╗");
        System.out.println("║     FILE PROCESS SERVICE STARTING       ║");
        System.out.println("╚═════════════════════════════════════════╝");

        ConfigurableApplicationContext context = SpringApplication.run(FileProcessApplication.class, args);

        MQConfig config = MQConfig.fromSystemPropertiesOrDefaults();
        FileProcessRequestListener listener = new FileProcessRequestListener(config);

        // Register shutdown hook so Ctrl+C cleans up gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n╔═════════════════════════════════════════╗");
            System.out.println("║         SHUTTING DOWN SERVICE           ║");
            System.out.println("╚═════════════════════════════════════════╝");
            listener.stop();
            context.close();
        }));

        listener.start();

        System.out.println("✓ File Process Service running. Press Ctrl+C to stop.\n");
    }
}