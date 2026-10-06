package gag.nails.identity;

/** Провайдер входа. {@link #FAKE} — подставной для тестов и локальной разработки; в бою его бина нет. */
public enum Provider {
    YANDEX,
    VK,
    FAKE
}
