package com.forward;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FileProcessApplication {

    public static void main(String[] args) {
        System.out.println("╔═════════════════════════════════════════╗");
        System.out.println("║     FILE PROCESS SERVICE STARTING       ║");
        System.out.println("╚═════════════════════════════════════════╝");
        SpringApplication.run(FileProcessApplication.class, args);
    }
}
