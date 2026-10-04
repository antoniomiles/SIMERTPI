package ec.gob.simertpi.api;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import java.util.*;
@RestControllerAdvice
public class OperationalErrorAdvice implements ResponseBodyAdvice<Object> {
 public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){return true;}
 public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
  if(body instanceof Map<?,?> map && map.get("status") instanceof Number status && status.intValue()>=400){
   Map<Object,Object> result=new LinkedHashMap<>(map);String correlation=org.slf4j.MDC.get("correlationId");if(correlation!=null)result.put("correlationId",correlation);return result;
  }
  return body;
 }
}
