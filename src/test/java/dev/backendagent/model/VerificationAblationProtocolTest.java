package dev.backendagent.model;
import dev.backendagent.config.ModelConfig;
import dev.backendagent.tools.ToolDefinition;
import dev.backendagent.verification.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VerificationAblationProtocolTest {
 @Test void disabledVariantOmitsOnlyBasisPromptAreaAndToolWhileDefaultWireStaysIdentical() throws Exception {
  var config=new ModelConfig("offline",java.net.URI.create("http://127.0.0.1"),"offline",java.time.Duration.ofSeconds(1),java.time.Duration.ofSeconds(1));
  var req=new ModelRequest("repair",List.of(),List.of(new ToolDefinition("pin_verification_basis","basis tool",Map.of()),new ToolDefinition("read_observation","history",Map.of())),5)
    .withVerificationContext(new VerificationContext(1,new VerificationState(),0,null,null,List.of()));
  var defaultClient=new DeepSeekModelClient(config);var enabled=new DeepSeekModelClient(config,true);var disabled=new DeepSeekModelClient(config,false);
  var original=defaultClient.buildRequest(req);assertEquals(original,enabled.buildRequest(req));var variant=disabled.buildRequest(req);
  assertFalse(variant.toString().contains("current_turn_verification"));assertFalse(variant.toString().contains("pin_verification_basis"));
  assertFalse(variant.toString().contains("该区每轮自动更新"));assertTrue(variant.toString().contains("read_observation"));assertTrue(variant.toString().contains("workspace_validation_state"));
  assertFalse(disabled.supportsVerificationUpdates());assertFalse(disabled.supportsVerificationCompaction());
 }
}
