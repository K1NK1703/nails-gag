package gag.nails.identity.model;

import gag.nails.identity.LoginFlow;

/** Исход {@link LoginFlow#complete}. */
public sealed interface LoginResult permits SignedIn, RegistrationRequired, LinkConfirmationRequired, Linked {}
