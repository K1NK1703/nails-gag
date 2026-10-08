package gag.nails.identity.internal.login;

/** Зачем выдан билет: завершить регистрацию или подтвердить объединение при привязке. */
enum PendingPurpose {
    REGISTER,
    LINK
}
