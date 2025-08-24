package controller;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ParseProblemException;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static utils.PrintUtils.*;

// Analyze AFMethod (before refactoring) and AFMethod2 (after refactoring)
// Calculate the metric in the two cases
public class RefactoringAnalysis {

    private static final Logger LOGGER = Logger.getLogger(RefactoringAnalysis.class.getName());

    public static void execute(String projectName, String methodName, String feature) {
        String dir = "refactoringFiles/" + projectName.toLowerCase();
        String originalFile = dir + "/AFMethod.java";
        String refactoredFile = dir + "/AFMethod2.java";
        String outputFile = dir + "/analysis_report_" + feature.toLowerCase() + ".csv";

        LOGGER.info("===================================================================");
        LOGGER.log(Level.INFO, "Start Refactoring Analysis for Project: {0}, Feature: {1}", new Object[]{projectName, feature});
        LOGGER.info("===================================================================");

        try {
            Files.createDirectories(Paths.get(dir));

            String originalContent = new String(Files.readAllBytes(Paths.get(originalFile)));
            String originalCodeToParse = "class DummyWrapperOriginal { " + originalContent + " }";
            CompilationUnit cuOriginal = StaticJavaParser.parse(originalCodeToParse);

            String refactoredContent = new String(Files.readAllBytes(Paths.get(refactoredFile)));
            String refactoredCodeToParse = "class DummyWrapperRefactored { " + refactoredContent + " }";
            CompilationUnit cuRefactored = StaticJavaParser.parse(refactoredCodeToParse);

            Optional<MethodDeclaration> originalMethodOpt = cuOriginal.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals(methodName));

            if (originalMethodOpt.isEmpty()) {
                LOGGER.log(Level.SEVERE, "ERRORE: Impossibile trovare il metodo originale ''{0}'' nel file.", methodName);
                return;
            }

            MethodDeclaration originalMethod = originalMethodOpt.get();
            Optional<MethodDeclaration> refactoredEntryPointOpt = cuRefactored.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals(methodName));

            List<MethodDeclaration> allRefactoredMethods = cuRefactored.findAll(MethodDeclaration.class);
            List<ConstructorDeclaration> allRefactoredConstructors = cuRefactored.findAll(ConstructorDeclaration.class);


            try (FileWriter fileWriter = new FileWriter(outputFile);
                 PrintWriter writer = new PrintWriter(fileWriter)) {

                writer.println("Method,Version,LOC,NumParameters,NumBranches,NestingDepth,NumCodeSmells");

                printMetricsForMethod(originalMethod, "Original", writer);

                writer.println("\n// --- Refactoring details ---");
                for (MethodDeclaration md : allRefactoredMethods) {
                    String tag = md.getNameAsString().equals(methodName) ? "Refactored_EntryPoint" : "Refactored_Helper";
                    printMetricsForMethod(md, tag, writer);
                }
                for (ConstructorDeclaration cd : allRefactoredConstructors) {
                    printMetricsForConstructor(cd, "Refactored_Helper", writer);
                }

                writer.println("\n// --- Aggregate Results ---");
                if (refactoredEntryPointOpt.isPresent()) {
                    printAggregatedMetrics(refactoredEntryPointOpt.get(), allRefactoredMethods, allRefactoredConstructors, writer);
                } else {
                    writer.println("ERRORE: Metodo entry point '" + methodName + "' non trovato per il riepilogo aggregato.");
                }
            }

            LOGGER.info("\nEnd Analysis");

        } catch (IOException | ParseProblemException e) {
            LOGGER.log(Level.SEVERE, "ERRORE durante l'analisi. Controlla i file di input e i percorsi.", e);
        }
    }


}