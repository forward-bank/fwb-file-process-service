package com.forward;

import com.forward.mq.MQConfig;
import com.forward.mq.listener.SyntaxValidationRequestListener;

public class SyntaxValidationApplication {

    public static void main(String[] args) {
        System.out.println("╔═════════════════════════════════════════╗");
        System.out.println("║   SYNTAX VALIDATION SERVICE STARTING    ║");
        System.out.println("╚═════════════════════════════════════════╝");

        MQConfig config = MQConfig.fromSystemPropertiesOrDefaults();
        SyntaxValidationRequestListener listener = new SyntaxValidationRequestListener(config);

        // Register shutdown hook so Ctrl+C cleans up gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n╔═════════════════════════════════════════╗");
            System.out.println("║         SHUTTING DOWN SERVICE           ║");
            System.out.println("╚═════════════════════════════════════════╝");
            listener.stop();
        }));

        listener.start();

        // Block main thread — listener runs on the MQ session thread
        System.out.println("✓ Syntax Validation Service running. Press Ctrl+C to stop.\n");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}