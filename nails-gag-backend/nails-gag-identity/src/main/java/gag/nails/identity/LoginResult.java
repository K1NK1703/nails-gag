package gag.nails.identity;

/** Исход {@link LoginFlow#complete}. */
public sealed interface LoginResult permits SignedIn, RegistrationRequired, LinkConfirmationRequired, Linked {}
