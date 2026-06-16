import java.util.*;
import java.io.*;
import java.nio.file.*;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            executeCommand(line);
        }
    }

    static void executeCommand(String line) throws Exception {
        // Parse redirection
        String stdoutFile = null;
        String stderrFile = null;
        boolean appendStdout = false;
        boolean appendStderr = false;

        // Find redirection operators
        List<String> tokens = tokenize(line);
        List<String> cmdTokens = new ArrayList<>();
        
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if ((t.equals(">") || t.equals("1>")) && i + 1 < tokens.size()) {
                stdoutFile = tokens.get(++i);
            } else if ((t.equals(">>") || t.equals("1>>")) && i + 1 < tokens.size()) {
                stdoutFile = tokens.get(++i);
                appendStdout = true;
            } else if (t.equals("2>") && i + 1 < tokens.size()) {
                stderrFile = tokens.get(++i);
            } else if (t.equals("2>>") && i + 1 < tokens.size()) {
                stderrFile = tokens.get(++i);
                appendStderr = true;
            } else {
                cmdTokens.add(t);
            }
        }

        if (cmdTokens.isEmpty()) return;
        String cmd = cmdTokens.get(0);
        List<String> cmdArgs = cmdTokens.subList(1, cmdTokens.size());

        // Setup output streams
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;

        if (stdoutFile != null) {
            File f = new File(stdoutFile);
            f.getParentFile().mkdirs();
            System.setOut(new PrintStream(new FileOutputStream(f, appendStdout)));
        }
        if (stderrFile != null) {
            File f = new File(stderrFile);
            f.getParentFile().mkdirs();
            System.setErr(new PrintStream(new FileOutputStream(f, appendStderr)));
        }

        try {
            switch (cmd) {
                case "exit" -> {
                    int code = cmdArgs.isEmpty() ? 0 : Integer.parseInt(cmdArgs.get(0));
                    System.exit(code);
                }
                case "echo" -> {
                    System.out.println(String.join(" ", cmdArgs));
                }
                case "type" -> {
                    if (cmdArgs.isEmpty()) break;
                    String target = cmdArgs.get(0);
                    if (isBuiltin(target)) {
                        System.out.println(target + " is a shell builtin");
                    } else {
                        String path = findInPath(target);
                        if (path != null) {
                            System.out.println(target + " is " + path);
                        } else {
                            System.out.println(target + ": not found");
                        }
                    }
                }
                case "pwd" -> {
                    System.out.println(System.getProperty("user.dir"));
                }
                case "cd" -> {
                    String dir = cmdArgs.isEmpty() ? System.getenv("HOME") : cmdArgs.get(0);
                    if (dir == null) dir = System.getProperty("user.home");
                    if (dir.equals("~")) {
                        dir = System.getenv("HOME");
                        if (dir == null) dir = System.getProperty("user.home");
                    }
                    File f = new File(dir);
                    if (!f.isAbsolute()) f = new File(System.getProperty("user.dir"), dir);
                    f = f.getCanonicalFile();
                    if (f.isDirectory()) {
                        System.setProperty("user.dir", f.getPath());
                    } else {
                        System.err.println("cd: " + dir + ": No such file or directory");
                    }
                }
                default -> {
                    String execPath = findInPath(cmd);
                    if (execPath != null) {
                        List<String> command = new ArrayList<>();
                        command.add(cmd);
                        command.addAll(cmdArgs);
                        ProcessBuilder pb = new ProcessBuilder(command);
                        pb.directory(new File(System.getProperty("user.dir")));
                        pb.environment().put("PATH", System.getenv("PATH"));

                        // Handle stdout redirection for external commands
                        if (stdoutFile != null) {
                            File f = new File(stdoutFile);
                            f.getParentFile().mkdirs();
                            pb.redirectOutput(appendStdout ?
                                ProcessBuilder.Redirect.appendTo(f) :
                                ProcessBuilder.Redirect.to(f));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }

                        if (stderrFile != null) {
                            File f = new File(stderrFile);
                            f.getParentFile().mkdirs();
                            pb.redirectError(appendStderr ?
                                ProcessBuilder.Redirect.appendTo(f) :
                                ProcessBuilder.Redirect.to(f));
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        Process p = pb.start();
                        p.waitFor();
                    } else {
                        System.err.println(cmd + ": command not found");
                    }
                }
            }
        } finally {
            System.out.flush();
            System.err.flush();
            if (stdoutFile != null) {
                System.out.close();
                System.setOut(savedOut);
            }
            if (stderrFile != null) {
                System.err.close();
                System.setErr(savedErr);
            }
        }
    }

    static boolean isBuiltin(String cmd) {
        return Set.of("echo", "exit", "type", "pwd", "cd").contains(cmd);
    }

    static String findInPath(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(":")) {
            File f = new File(dir, cmd);
            if (f.isFile() && f.canExecute()) return f.getPath();
        }
        return null;
    }

    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\'') {
                i++;
                while (i < line.length() && line.charAt(i) != '\'') {
                    current.append(line.charAt(i++));
                }
                i++;
            } else if (c == '"') {
                i++;
                while (i < line.length() && line.charAt(i) != '"') {
                    if (line.charAt(i) == '\\' && i + 1 < line.length()) {
                        char next = line.charAt(i + 1);
                        if (next == '"' || next == '\\' || next == '$' || next == '\n') {
                            current.append(next); i += 2;
                        } else {
                            current.append('\\'); current.append(next); i += 2;
                        }
                    } else {
                        current.append(line.charAt(i++));
                    }
                }
                i++;
            } else if (c == '\\') {
                if (i + 1 < line.length()) { current.append(line.charAt(i + 1)); i += 2; }
                else i++;
            } else if (c == ' ') {
                if (current.length() > 0) { tokens.add(current.toString()); current.setLength(0); }
                i++;
            } else {
                current.append(c); i++;
            }
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }
}