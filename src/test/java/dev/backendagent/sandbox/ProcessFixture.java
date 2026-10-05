package dev.backendagent.sandbox;

/** Child JVM fixture; commands never come from a model. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "output" -> {
                System.out.println("stdout " + args[1]);
                System.err.println("stderr");
                System.exit(7);
            }
            case "flood" -> {
                System.out.print("BEGIN\n" + "x".repeat(100000) + "\nEND\n");
            }
            case "sleep" -> Thread.sleep(60000);
            default -> throw new IllegalArgumentException();
        }
    }
}
