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

            List<String> tokens = tokenize(line);
            if (tokens.isEmpty()) continue;

            String cmd = tokens.get(0);
            List<String> cmdArgs = tokens.subList(1, tokens.size());

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
                    String dir = cmdArgs.isEmpty() ? System.getProperty("user.home") : cmdArgs.get(0);
                    if (dir.equals("~")) dir = System.getProperty("user.home");
                    File f = new File(dir);
                    if (!f.isAbsolute()) f = new File(System.getProperty("user.dir"), dir);
                    f = f.getCanonicalFile();
                    if (f.isDirectory()) {
                        System.setProperty("user.dir", f.getPath());
                    } else {
                        System.out.println("cd: " + dir + ": No such file or directory");
                    }
                }
                default -> {
                    String execPath = findInPath(cmd);
                    if (execPath != null) {
                        List<String> command = new ArrayList<>();
                        command.add(execPath);
                        command.addAll(cmdArgs);
                        ProcessBuilder pb = new ProcessBuilder(command);
                        pb.directory(new File(System.getProperty("user.dir")));
                        pb.inheritIO();
                        Process p = pb.start();
                        p.waitFor();
                    } else {
                        System.out.println(cmd + ": command not found");
                    }
                }
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
                i++; // closing quote
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
                i++; // closing quote
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