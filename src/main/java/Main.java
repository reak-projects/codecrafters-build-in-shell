import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Main {
    // Job table
    static final Map<Integer, JobEntry> jobs = new LinkedHashMap<>();
    static final AtomicInteger nextJobId = new AtomicInteger(1);

    static class JobEntry {
        int id;
        Process process;
        String command;
        boolean done = false;
        int exitCode = -1;

        JobEntry(int id, Process process, String command) {
            this.id = id;
            this.process = process;
            this.command = command;
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            reapJobs(); // reap before prompt
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            executeCommand(line);
        }
    }

    static void reapJobs() {
        List<Integer> toRemove = new ArrayList<>();
        for (JobEntry job : jobs.values()) {
            if (!job.done && !job.process.isAlive()) {
                job.done = true;
                job.exitCode = job.process.exitValue();
                System.out.printf("[%d]%c  %-21s%s%n", job.id, '+', "Done", job.command);
                toRemove.add(job.id);
            }
        }
        for (int id : toRemove) jobs.remove(id);
        // Recycle job numbers
        if (jobs.isEmpty()) nextJobId.set(1);
    }

    static void executeCommand(String line) throws Exception {
        boolean background = false;
        if (line.endsWith("&")) {
            background = true;
            line = line.substring(0, line.length() - 1).trim();
        }

        // Parse redirection
        String stdoutFile = null;
        String stderrFile = null;
        boolean appendStdout = false;
        boolean appendStderr = false;

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

        // Builtins don't run in background
        switch (cmd) {
            case "exit" -> {
                int code = cmdArgs.isEmpty() ? 0 : Integer.parseInt(cmdArgs.get(0));
                System.exit(code);
            }
            case "echo" -> {
                PrintStream out = getOutStream(stdoutFile, appendStdout);
                ensureFileCreated(stderrFile, appendStderr);
                out.println(String.join(" ", cmdArgs));
                if (stdoutFile != null) out.close();
                return;
            }
            case "type" -> {
                if (cmdArgs.isEmpty()) return;
                String target = cmdArgs.get(0);
                PrintStream out = getOutStream(stdoutFile, appendStdout);
                ensureFileCreated(stderrFile, appendStderr);
                if (isBuiltin(target)) {
                    out.println(target + " is a shell builtin");
                } else {
                    String path = findInPath(target);
                    if (path != null) {
                        out.println(target + " is " + path);
                    } else {
                        out.println(target + ": not found");
                    }
                }
                if (stdoutFile != null) out.close();
                return;
            }
            case "pwd" -> {
                PrintStream out = getOutStream(stdoutFile, appendStdout);
                ensureFileCreated(stderrFile, appendStderr);
                out.println(System.getProperty("user.dir"));
                if (stdoutFile != null) out.close();
                return;
            }
            case "cd" -> {
                ensureFileCreated(stdoutFile, appendStdout);
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
                    PrintStream err = getOutStream(stderrFile, appendStderr);
                    err.println("cd: " + dir + ": No such file or directory");
                    if (stderrFile != null) err.close();
                }
                return;
            }
            case "jobs" -> {
                ensureFileCreated(stderrFile, appendStderr);
                PrintStream out = getOutStream(stdoutFile, appendStdout);
                // Update done status before listing
                List<Integer> toRemoveAfter = new ArrayList<>();
                for (JobEntry job : jobs.values()) {
                    if (!job.done && !job.process.isAlive()) {
                        job.done = true;
                        job.exitCode = job.process.exitValue();
                    }
                }
                List<JobEntry> allJobs = new ArrayList<>(jobs.values());
                for (int ji = 0; ji < allJobs.size(); ji++) {
                    JobEntry job = allJobs.get(ji);
                    char flag = (ji == allJobs.size() - 1) ? '+' :
                                (ji == allJobs.size() - 2) ? '-' : ' ';
                    if (job.done) {
                        out.printf("[%d]%c  %-21s%s%n", job.id, flag, "Done", job.command);
                        toRemoveAfter.add(job.id);
                    } else {
                        out.printf("[%d]%c  %-24s%s &%n", job.id, flag, "Running", job.command);
                    }
                }
                for (int id : toRemoveAfter) jobs.remove(id);
                if (jobs.isEmpty()) nextJobId.set(1);
                if (stdoutFile != null) out.close();
                return;
            }
        }

        // External command
        String execPath = findInPath(cmd);
        if (execPath == null) {
            System.err.println(cmd + ": command not found");
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(cmd);
        command.addAll(cmdArgs);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.environment().put("PATH", System.getenv("PATH"));

        if (background) {
            // Background: capture output, print later
            pb.redirectErrorStream(false);
            Process p = pb.start();
            int jobId = nextJobId.getAndIncrement();
            String jobCmd = cmd + (cmdArgs.isEmpty() ? "" : " " + String.join(" ", cmdArgs));
            JobEntry entry = new JobEntry(jobId, p, jobCmd);
            jobs.put(jobId, entry);
            System.out.println("[" + jobId + "] " + p.pid());

            // Stream output in background thread
            Thread outThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String outputLine;
                    while ((outputLine = reader.readLine()) != null) {
                        System.out.println(outputLine);
                    }
                } catch (IOException e) {}
            });
            outThread.setDaemon(true);
            outThread.start();
        } else {
            // Foreground
            if (stdoutFile != null) {
                File f = new File(stdoutFile);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                pb.redirectOutput(appendStdout ? ProcessBuilder.Redirect.appendTo(f) : ProcessBuilder.Redirect.to(f));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }
            if (stderrFile != null) {
                File f = new File(stderrFile);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                pb.redirectError(appendStderr ? ProcessBuilder.Redirect.appendTo(f) : ProcessBuilder.Redirect.to(f));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            p.waitFor();
        }
    }

    static PrintStream getOutStream(String file, boolean append) throws IOException {
        if (file == null) return System.out;
        File f = new File(file);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        return new PrintStream(new FileOutputStream(f, append));
    }

    static void ensureFileCreated(String file, boolean append) throws IOException {
        if (file == null) return;
        File f = new File(file);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        // In truncate mode always touch it; in append mode only create if missing
        if (!append || !f.exists()) {
            new FileOutputStream(f, append).close();
        }
    }

    static boolean isBuiltin(String cmd) {
        return Set.of("echo", "exit", "type", "pwd", "cd", "jobs").contains(cmd);
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