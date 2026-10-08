package gag.nails.identity.model;

/** Провайдер входа. {@link #FAKE} — подставной для тестов и локальной разработки; в бою его бина нет. */
public enum Provider {
    YANDEX,
    VK,
    FAKE
}
