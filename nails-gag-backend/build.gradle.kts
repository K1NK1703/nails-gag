// Корневой проект ничего не собирает: всё в подпроектах, общие настройки — в build-logic.
// Плагин Spring Boot объявлен здесь с apply false, чтобы он грузился одним classloader'ом для
// всех подпроектов: иначе Spotless из convention-плагина в app и common оказывается в разных
// classloader'ах и общий build service не создаётся.
plugins {
    alias(libs.plugins.spring.boot) apply false
}

group = "gag.nails"
version = "0.1.0-SNAPSHOT"
