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

import static utils.PrintUtils.*;

/**
 * Analizza due file separati, uno per il metodo originale (AFMethod.java) e uno
 * per il sistema rifattorizzato (AFMethod2.java).
 * Calcola le metriche usando i calcolatori specifici e produce un report CSV.
 */
public class RefactoringAnalysis {

    public static void execute(String projectName, String methodName, String feature) {
        String dir = "refactoringFiles/" + projectName.toLowerCase();
        String originalFile = dir + "/AFMethod.java";
        String refactoredFile = dir + "/AFMethod2.java";
        String outputFile = dir + "/analysis_report_" + feature.toLowerCase() + ".csv";

        System.out.println("===================================================================");
        System.out.printf("Start Refactoring Analysis for Project: %s, Feature: %s%n", projectName, feature);
        System.out.println("===================================================================");

        try {
            Files.createDirectories(Paths.get(dir));

            // --- LETTURA E PARSING ---
            // Legge il file originale e lo avvolge in una classe fittizia
            String originalContent = new String(Files.readAllBytes(Paths.get(originalFile)));
            String originalCodeToParse = "class DummyWrapperOriginal { " + originalContent + " }";
            CompilationUnit cuOriginal = StaticJavaParser.parse(originalCodeToParse);

            // Legge il file rifattorizzato e lo avvolge in un'altra classe fittizia
            String refactoredContent = new String(Files.readAllBytes(Paths.get(refactoredFile)));
            String refactoredCodeToParse = "class DummyWrapperRefactored { " + refactoredContent + " }";
            CompilationUnit cuRefactored = StaticJavaParser.parse(refactoredCodeToParse);

            // --- ESTRAZIONE METODI ---
            Optional<MethodDeclaration> originalMethodOpt = cuOriginal.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals(methodName));

            if (!originalMethodOpt.isPresent()) {
                System.err.printf("ERRORE: Impossibile trovare il metodo originale '%s' nel file.%n", methodName);
                return;
            }

            MethodDeclaration originalMethod = originalMethodOpt.get();
            Optional<MethodDeclaration> refactoredEntryPointOpt = cuRefactored.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals(methodName));

            List<MethodDeclaration> allRefactoredMethods = cuRefactored.findAll(MethodDeclaration.class);
            List<ConstructorDeclaration> allRefactoredConstructors = cuRefactored.findAll(ConstructorDeclaration.class);


            // --- SCRITTURA REPORT CSV ---
            try (FileWriter fileWriter = new FileWriter(outputFile);
                 PrintWriter writer = new PrintWriter(fileWriter)) {

                writer.println("Method,Version,LOC,NumParameters,NumBranches,NestingDepth,NumCodeSmells");

                // Stampa le metriche per il metodo originale
                printMetricsForMethod(originalMethod, "Original", writer);

                // Stampa il dettaglio per ogni parte del sistema rifattorizzato
                writer.println("\n// --- Refactoring details ---");
                for (MethodDeclaration md : allRefactoredMethods) {
                    String tag = md.getNameAsString().equals(methodName) ? "Refactored_EntryPoint" : "Refactored_Helper";
                    printMetricsForMethod(md, tag, writer);
                }
                for (ConstructorDeclaration cd : allRefactoredConstructors) {
                    printMetricsForConstructor(cd, "Refactored_Helper", writer);
                }

                // Stampa il riepilogo aggregato per il confronto
                writer.println("\n// --- Aggregate Results ---");
                if (refactoredEntryPointOpt.isPresent()) {
                    printAggregatedMetrics(refactoredEntryPointOpt.get(), allRefactoredMethods, allRefactoredConstructors, writer);
                } else {
                    writer.println("ERRORE: Metodo entry point '" + methodName + "' non trovato per il riepilogo aggregato.");
                }
            }

            System.out.println("\nEnd Analysis");

        } catch (IOException | ParseProblemException e) {
            System.err.println("ERRORE durante l'analisi. Controlla i file di input e i percorsi.");
            e.printStackTrace();
        }
    }


}