package com.iwrite.audit.annotation;

import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResourceType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedOperation {

    AuditAction action();

    AuditResourceType resourceType();

    String resourceId();
}
