package telemetry;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class SmokeTest {@Test void parsesOrdinaryLine(){assertEquals(200,LogParser.parse("1|api|200|10").status);}}
