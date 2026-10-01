package gag.nails;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Корень приложения для тестов доменных модулей. Лежит в {@code gag.nails}, чтобы Spring Modulith
 * считал модулями пакеты {@code gag.nails.*}, оказавшиеся на тестовом classpath модуля.
 * В {@code nails-gag-app} не используется: у него свой класс приложения.
 */
@SpringBootApplication
public class TestApplication {}
