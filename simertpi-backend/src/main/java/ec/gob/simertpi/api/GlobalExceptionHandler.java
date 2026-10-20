package ec.gob.simertpi.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ParkingConflictException.class)
    public ResponseEntity<Map<String,Object>> parkingConflict(ParkingConflictException exception) {
        return ResponseEntity.status(409).body(Map.of("code",exception.code(),"status",409,"message",exception.userMessage()));
    }

    @ExceptionHandler(ec.gob.simertpi.application.identity.InspectorAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> inspectorAlreadyExists(
            ec.gob.simertpi.application.identity.InspectorAlreadyExistsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", 409, "error", "Conflict", "message", exception.getMessage()));
    }

    @ExceptionHandler(ec.gob.simertpi.application.identity.InspectorNotFoundException.class)
    public ResponseEntity<Map<String, Object>> inspectorNotFound(
            ec.gob.simertpi.application.identity.InspectorNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404, "error", "Not Found", "message", exception.getMessage()));
    }

    @ExceptionHandler(ec.gob.simertpi.application.identity.InspectorActivationStateException.class)
    public ResponseEntity<Map<String, Object>> inspectorActivationState(
            ec.gob.simertpi.application.identity.InspectorActivationStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", 409, "error", "Conflict", "message", exception.getMessage()));
    }

    @ExceptionHandler(ec.gob.simertpi.application.identity.InspectorActivationRejectedException.class)
    public ResponseEntity<Map<String, Object>> inspectorActivationRejected(
            ec.gob.simertpi.application.identity.InspectorActivationRejectedException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400, "error", "Activation Rejected", "message", exception.getMessage()));
    }

    @ExceptionHandler(ec.gob.simertpi.domain.vehicles.VehicleAssociationConflictException.class)
    public ResponseEntity<Map<String, Object>> handleVehicleAssociationConflict(
            ec.gob.simertpi.domain.vehicles.VehicleAssociationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "timestamp", OffsetDateTime.now(), "status", 409,
                "error", "Conflict", "message", exception.getMessage()));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String,Object>> handleAccessDenied(Exception exception) {
        return ResponseEntity.status(403).body(Map.of("status",403,"error","Forbidden","message","Access denied"));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<Map<String,Object>> handleAuthentication(Exception exception) {
        return ResponseEntity.status(401).body(Map.of("status",401,"error","Unauthorized","message","Authentication required"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> handleOperationalFailure(Exception exception, HttpServletRequest request) {
        if(exception instanceof org.springframework.web.ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            int status=error.getStatusCode().value();
            OperationalRequestLogFilter.mark(request, status == 409 ? "BUSINESS_CONFLICT" : "REQUEST_REJECTED", exception);
            return ResponseEntity.status(status).body(Map.of("status",status,"error","Request rejected","message","Request could not be accepted"));
        }
        OperationalRequestLogFilter.mark(request, "UNEXPECTED_ERROR", exception);
        return ResponseEntity.status(500).body(Map.of("status",500,"error","Internal Server Error",
                "message","Operational request failed"));
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException exception, HttpServletRequest request) {
        OperationalRequestLogFilter.mark(request, "INVALID_IDEMPOTENCY_KEY", exception);
        return ResponseEntity.badRequest().body(Map.of(
                "timestamp", OffsetDateTime.now(), "status", 400,
                "error", "Bad Request", "message", exception.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(InvalidRequestException exception, HttpServletRequest request) {
        OperationalRequestLogFilter.mark(request, "INVALID_REQUEST", exception);
        return ResponseEntity.badRequest().body(Map.of(
                "timestamp", OffsetDateTime.now(), "status", 400,
                "error", "Bad Request", "message", exception.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(
            IdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "timestamp", OffsetDateTime.now(), "status", 409,
                "error", "Conflict", "message", exception.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityConflict(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        OperationalRequestLogFilter.mark(request, "DATA_INTEGRITY_CONFLICT", exception);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "timestamp", OffsetDateTime.now(), "status", 409,
                "error", "Conflict",
                "message", "El recurso solicitado ya no está disponible."));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "timestamp", OffsetDateTime.now(), "status", 403,
                "error", "Forbidden", "message", exception.getMessage()));
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationRequired(
            AuthenticationRequiredException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "timestamp", OffsetDateTime.now(), "status", 401,
                "error", "Unauthorized", "message", exception.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFoundException(
            ResourceNotFoundException exception) {

        Map<String, Object> response = Map.of(
                "timestamp", OffsetDateTime.now(),
                "status", HttpStatus.NOT_FOUND.value(),
                "error", "Not Found",
                "message", exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
            IllegalArgumentException exception, HttpServletRequest request) {
        OperationalRequestLogFilter.mark(request, "BUSINESS_RULE_CONFLICT", exception);

        Map<String, Object> response = Map.of(
                "timestamp", OffsetDateTime.now(),
                "status", HttpStatus.CONFLICT.value(),
                "error", "Conflict",
                "message", exception.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        OperationalRequestLogFilter.mark(request, "VALIDATION_ERROR", exception);

        Map<String, String> fields = new HashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        fields.put(error.getField(), error.getDefaultMessage())
                );

        Map<String, Object> response = Map.of(
                "timestamp", OffsetDateTime.now(),
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", "Validation Error",
                "message", "Existen errores de validacion",
                "fields", fields
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }
}
