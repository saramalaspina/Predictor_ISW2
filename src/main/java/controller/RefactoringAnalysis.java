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

/**
 * Analizza due file separati, uno per il metodo originale (AFMethod.java) e uno
 * per il sistema rifattorizzato (AFMethod2.java).
 * Calcola le metriche usando i calcolatori specifici e produce un report CSV.
 */
public class RefactoringAnalysis {

    public void execute(String projectName, String feature) {
        // --- CONFIGURAZIONE ---
        String methodName;

        if ("BOOKKEEPER".equalsIgnoreCase(projectName)) {
            methodName = "main";
        } else if ("OPENJPA".equalsIgnoreCase(projectName)) {
            methodName = "ilTuoMetodoOriginale";
        } else {
            System.err.println("Progetto non riconosciuto: " + projectName);
            return;
        }

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

    /**
     * Calcola e scrive le metriche per un singolo MethodDeclaration.
     * UTILIZZA I TUOI METODI DI CALCOLO.
     */
    private void printMetricsForMethod(MethodDeclaration md, String versionTag, PrintWriter writer) {
        int loc = MetricCalculator.calculateLOC(md);
        int numParams = md.getParameters().size();
        int numBranches = MetricCalculator.calculateNumberOfBranches(md);
        int nestingDepth = MetricCalculator.calculateNestingDepth(md);
        int numSmells = MetricCalculator.calculateCodeSmells(md, numBranches, loc, nestingDepth, numParams);

        writer.printf("%s,%s,%d,%d,%d,%d,%d%n", md.getNameAsString(), versionTag, loc, numParams, numBranches, nestingDepth, numSmells);
    }

    /**
     * Calcola e scrive le metriche per un singolo ConstructorDeclaration.
     * UTILIZZA I TUOI METODI DI CALCOLO.
     */
    private void printMetricsForConstructor(ConstructorDeclaration cd, String versionTag, PrintWriter writer) {
        MethodDeclaration fakeMethod = new MethodDeclaration().setBody(cd.getBody());
        cd.getParameters().forEach(fakeMethod::addParameter);

        int loc = MetricCalculator.calculateLOC(fakeMethod);
        int numParams = cd.getParameters().size();
        int numBranches = MetricCalculator.calculateNumberOfBranches(fakeMethod);
        int nestingDepth = MetricCalculator.calculateNestingDepth(fakeMethod);
        int numSmells = MetricCalculator.calculateCodeSmells(fakeMethod, numBranches, loc, nestingDepth, numParams);

        writer.printf("%s (constructor),%s,%d,%d,%d,%d,%d%n", cd.getNameAsString(), versionTag, loc, numParams, numBranches, nestingDepth, numSmells);
    }

    /**
     * Calcola e scrive le metriche aggregate per l'intero sistema rifattorizzato.
     */
    private void printAggregatedMetrics(MethodDeclaration mainRefactored, List<MethodDeclaration> allMethods, List<ConstructorDeclaration> allConstructors, PrintWriter writer) {
        int totalLoc = 0;
        int totalBranches = 0;
        int maxNesting = 0;
        int totalSmells = 0;

        for (MethodDeclaration md : allMethods) {
            totalLoc += MetricCalculator.calculateLOC(md);
            totalBranches += MetricCalculator.calculateNumberOfBranches(md);
            int nesting = MetricCalculator.calculateNestingDepth(md);
            if (nesting > maxNesting) maxNesting = nesting;
            totalSmells += MetricCalculator.calculateCodeSmells(md, MetricCalculator.calculateNumberOfBranches(md), MetricCalculator.calculateLOC(md), nesting, md.getParameters().size());
        }

        for (ConstructorDeclaration cd : allConstructors) {
            MethodDeclaration fakeMethod = new MethodDeclaration().setBody(cd.getBody());
            cd.getParameters().forEach(fakeMethod::addParameter);
            totalLoc += MetricCalculator.calculateLOC(fakeMethod);
            totalBranches += MetricCalculator.calculateNumberOfBranches(fakeMethod);
            int nesting = MetricCalculator.calculateNestingDepth(fakeMethod);
            if (nesting > maxNesting) maxNesting = nesting;
            totalSmells += MetricCalculator.calculateCodeSmells(fakeMethod, MetricCalculator.calculateNumberOfBranches(fakeMethod), MetricCalculator.calculateLOC(fakeMethod), nesting, cd.getParameters().size());
        }

        int mainParams = mainRefactored.getParameters().size();

        writer.printf("%s (aggregated),Refactored_Aggregate,%d,%d,%d,%d,%d%n",
                mainRefactored.getNameAsString(), totalLoc, mainParams, totalBranches, maxNesting, totalSmells);
    }

    /**
     * Punto di ingresso per eseguire l'analisi.
     */
    public static void main(String[] args) {
        RefactoringAnalysis analyzer = new RefactoringAnalysis();
        analyzer.execute("BOOKKEEPER", "LOC");
    }
}