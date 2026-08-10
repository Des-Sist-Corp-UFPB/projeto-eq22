package com.iwrite.audit.aspect;

import com.iwrite.audit.annotation.AuditedOperation;
import com.iwrite.audit.entity.AuditResult;
import com.iwrite.audit.service.AuditLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.UUID;

@Aspect
@Component
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public AuditLogAspect(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Around("@annotation(auditedOperation)")
    public Object audit(ProceedingJoinPoint joinPoint, AuditedOperation auditedOperation) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable operationFailure) {
            try {
                auditLogService.record(
                        auditedOperation.action(),
                        auditedOperation.resourceType(),
                        resolveResourceId(auditedOperation.resourceId(), method, joinPoint.getArgs(), null),
                        AuditResult.FAILED
                );
            } catch (RuntimeException auditFailure) {
                operationFailure.addSuppressed(auditFailure);
            }
            throw operationFailure;
        }

        auditLogService.record(
                auditedOperation.action(),
                auditedOperation.resourceType(),
                resolveResourceId(auditedOperation.resourceId(), method, joinPoint.getArgs(), result),
                AuditResult.SUCCEEDED
        );
        return result;
    }

    private UUID resolveResourceId(String expression, Method method, Object[] arguments, Object result) {
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                result,
                method,
                arguments,
                parameterNameDiscoverer
        );
        context.setVariable("result", result);
        return expressionParser.parseExpression(expression).getValue(context, UUID.class);
    }
}
