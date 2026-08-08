package com.iwrite.user.entity;

/**
 * Declarative only: personalizes the product, never grants access. No authorization check may
 * ever branch on a persona.
 */
public enum UserPersonaType {
    WRITER,
    EDITOR,
    REVIEWER,
    BETA_READER,
    OTHER
}
