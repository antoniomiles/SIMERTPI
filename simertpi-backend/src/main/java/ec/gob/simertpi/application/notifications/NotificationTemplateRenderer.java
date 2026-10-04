package ec.gob.simertpi.application.notifications;
import java.util.Map;
import java.util.regex.Pattern;
/** Internal plain text templates: no HTML interpreter and no provider template identifiers. */
public final class NotificationTemplateRenderer {
 private static final Pattern VARIABLE=Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");
 private NotificationTemplateRenderer() {}
 public static String render(String template,Map<String,?> values,int limit){
  if(template==null)throw new IllegalStateException("Notification template required");
  var matcher=VARIABLE.matcher(template);StringBuilder result=new StringBuilder();
  while(matcher.find()){
   String key=matcher.group(1);
   if(values==null||!values.containsKey(key)||values.get(key)==null)throw new IllegalStateException("Notification template variable required");
   Object value=values.get(key);
   if(!(value instanceof String||value instanceof Number||value instanceof Boolean||value instanceof java.util.UUID||value instanceof java.time.temporal.TemporalAccessor))throw new IllegalStateException("Unsupported template variable");
   String safe=value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
   matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(safe));
  }
  matcher.appendTail(result);String text=result.toString();return text.length()<=limit?text:text.substring(0,limit);
 }
}
