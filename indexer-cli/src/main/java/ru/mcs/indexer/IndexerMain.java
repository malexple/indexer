package ru.mcs.indexer;

import picocli.CommandLine;
import ru.mcs.indexer.cli.IndexerCommand;

import java.io.PrintWriter;

public final class IndexerMain {

    private IndexerMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String[] args) {
        IndexerCommand command = new IndexerCommand();
        CommandLine commandLine = new CommandLine(command);

        commandLine.setParameterExceptionHandler((ex, rawArgs) -> {
            PrintWriter err = ex.getCommandLine().getErr();
            err.println("error: " + ex.getMessage());
            err.println("Try '--help' for usage.");
            err.flush();
            return 1;
        });

        commandLine.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
            PrintWriter err = cmd.getErr();
            err.println("fatal: " + ex);
            if (command.isVerbose()) {
                ex.printStackTrace(err);
            }
            err.flush();
            return 2;
        });

        return commandLine.execute(args);
    }
}