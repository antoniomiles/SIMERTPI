package ec.gob.simertpi.application.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class AuditAspect {
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public AuditAspect(AuditService audit, TransactionTemplate transactions) {
        this.audit = audit;
        this.transactions = transactions;
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        try {
            return transactions.execute(status -> {
                try {
                    Object result = joinPoint.proceed();
                    if (result instanceof Number count && count.longValue() == 0) return result;
                    UUID resourceId = argumentUuid(joinPoint, audited.resourceIdArgument());
                    if (resourceId == null) resourceId = uuidProperty(result, "getId");
                    audit.success(audited.action(), audited.resourceType(), resourceId,
                            stringArgument(joinPoint, audited.idempotencyArgument()), Map.of());
                    return result;
                } catch (Throwable failure) {
                    status.setRollbackOnly();
                    throw new AuditedInvocationFailure(failure);
                }
            });
        } catch (AuditedInvocationFailure wrapped) {
            Throwable failure = wrapped.getCause();
            audit.failure(audited.action(), audited.resourceType(),
                    argumentUuid(joinPoint, audited.resourceIdArgument()), failure,
                    stringArgument(joinPoint, audited.idempotencyArgument()));
            throw failure;
        }
    }

    private UUID argumentUuid(JoinPoint point, int index) {
        Object[] args = point.getArgs();
        if (index < 0 || index >= args.length) return null;
        if (args[index] instanceof UUID id) return id;
        return uuidProperty(args[index], "getId");
    }

    private String stringArgument(JoinPoint point, int index) {
        Object[] args = point.getArgs();
        return index < 0 || index >= args.length || args[index] == null ? null : args[index].toString();
    }

    private UUID uuidProperty(Object value, String getter) {
        if (value == null) return null;
        try {
            Method method = value.getClass().getMethod(getter);
            Object id = method.invoke(value);
            return id instanceof UUID uuid ? uuid : null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static final class AuditedInvocationFailure extends RuntimeException {
        private AuditedInvocationFailure(Throwable cause) {
            super(cause);
        }
    }
}
