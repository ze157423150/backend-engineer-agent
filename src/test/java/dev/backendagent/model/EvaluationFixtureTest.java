package dev.backendagent.model;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Files;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Only compiles trusted static fixture code; model-generated code is always graded in Docker. */
class EvaluationFixtureTest {
    @TempDir Path root;
    @TestFactory
    java.util.stream.Stream<DynamicTest> checksTrustedSeedsAndReferenceSolutionsAgainstPrivateContracts() {
        return java.util.stream.Stream.concat(EvaluationFixture.tasks().stream(),
                java.util.stream.Stream.of(EvaluationFixture.sequentialHistoryTask(), EvaluationFixture.longHistoryTask())).flatMap(fixture -> java.util.stream.Stream.of(false, true).map(correct ->
                DynamicTest.dynamicTest(fixture.id + (correct ? " reference passes" : " seed fails"), () -> {
                    Path directory = root.resolve(fixture.id + "-" + correct);
                    fixture.write(directory, "trusted fixture", correct, true);
                    Path classes = Files.createDirectory(directory.resolve("classes"));
                    var compiler = ToolProvider.getSystemJavaCompiler();
                    assertNotNull(compiler, "A full JDK is required");
                    try (var manager = compiler.getStandardFileManager(null, null, null)) {
                        var units = manager.getJavaFileObjects(directory.resolve("src/main/java/dev/eval/FixTarget.java").toFile(),
                                directory.resolve("src/test/java/dev/eval/FixTargetTest.java").toFile());
                        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                        assertTrue(compiler.getTask(null, manager, null, java.util.List.of("-classpath", classpath, "-d", classes.toString()), null, units).call());
                    }
                    try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
                        var type = loader.loadClass("dev.eval.FixTargetTest");
                        var constructor = type.getDeclaredConstructor();
                        constructor.setAccessible(true);
                        var instance = constructor.newInstance();
                        var contract = type.getDeclaredMethod("contract");
                        contract.setAccessible(true);
                        if (correct) { assertDoesNotThrow(() -> contract.invoke(instance)); }
                        else {
                            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> contract.invoke(instance));
                            assertTrue(failure.getCause() instanceof org.opentest4j.AssertionFailedError
                                    || failure.getCause() instanceof NullPointerException);
                        }
                    }
                })));
    }
}
