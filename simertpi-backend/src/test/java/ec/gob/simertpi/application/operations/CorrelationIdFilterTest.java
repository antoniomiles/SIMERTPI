package ec.gob.simertpi.application.operations;
import ec.gob.simertpi.api.CorrelationIdFilter;
import org.springframework.mock.web.*;
import org.slf4j.MDC;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CorrelationIdFilterTest {
 @ParameterizedTest @ValueSource(strings={"valid-correlation:1","","invalid\r\nINJECT","xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
 void validatesAndCleansMdc(String supplied)throws Exception{MDC.clear();var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();request.addHeader("X-Correlation-ID",supplied);new CorrelationIdFilter().doFilter(request,response,(a,b)->assertThat(MDC.get("correlationId")).isEqualTo(response.getHeader("X-Correlation-ID")));String actual=response.getHeader("X-Correlation-ID");assertThat(actual).matches("[A-Za-z0-9._:-]{1,128}");if(supplied.equals("valid-correlation:1"))assertThat(actual).isEqualTo(supplied);else assertThat(actual).isNotEqualTo(supplied);assertThat(MDC.get("correlationId")).isNull();}
 @Test void missingCorrelationIsGeneratedEvenOnFailure()throws Exception{MDC.clear();var response=new MockHttpServletResponse();try{new CorrelationIdFilter().doFilter(new MockHttpServletRequest(),response,(a,b)->{throw new jakarta.servlet.ServletException("fixture-secret");});}catch(jakarta.servlet.ServletException expected){}assertThat(response.getHeader("X-Correlation-ID")).isNotBlank();assertThat(MDC.get("correlationId")).isNull();}
}
