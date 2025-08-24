package controller;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

// Calculate Spearman Correlation from dataset

public class CorrelationCalculator {
    private static final Logger LOGGER = Logger.getLogger(CorrelationCalculator.class.getName());

    private CorrelationCalculator() {}

    public static void calculateAndSave(String projectName) throws IOException {
        String inputFilePath = String.format("reportFiles/%s/Dataset.csv", projectName.toLowerCase());
        String outputDir = "reportFiles/" + projectName.toLowerCase() + "/";
        String outputFileName = "Correlation.csv";

        File inputFile = new File(inputFilePath);
        if (!inputFile.exists()) {
            LOGGER.severe("Dataset.csv not found at path: " + inputFilePath);
            return;
        }

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (Reader reader = new FileReader(inputFile);
             CSVParser csvParser = CSVParser.parse(reader, csvFormat)) {

            Map<String, Integer> headerMap = csvParser.getHeaderMap();
            if (headerMap == null) {
                LOGGER.severe("Could not read headers from CSV.");
                return;
            }

            List<String> numericColumns = new ArrayList<>();
            Map<String, List<Double>> featureValues = new HashMap<>();
            List<Double> labelValues = new ArrayList<>();

            final Set<String> nonNumericHeaders = Set.of("FullyQualifiedName", "Buggy");

            for (String header : headerMap.keySet()) {
                if (!nonNumericHeaders.contains(header)) {
                    numericColumns.add(header);
                    featureValues.put(header, new ArrayList<>());
                }
            }

            if (!headerMap.containsKey("Buggy")) {
                LOGGER.severe("Label column 'Buggy' not found in Dataset.csv");
                return;
            }

            for (CSVRecord record : csvParser) {
                for (String feature : numericColumns) {
                    try {
                        featureValues.get(feature).add(Double.parseDouble(record.get(feature)));
                    } catch (NumberFormatException e) {
                        featureValues.get(feature).add(0.0);
                    }
                }
                String label = record.get("Buggy").trim().toLowerCase();
                labelValues.add(label.equals("yes") ? 1.0 : 0.0);
            }

            SpearmansCorrelation correlation = new SpearmansCorrelation();
            List<String[]> correlationResults = new ArrayList<>();
            double[] labelArray = labelValues.stream().mapToDouble(Double::doubleValue).toArray();

            for (String feature : numericColumns) {
                double[] featureArray = featureValues.get(feature).stream().mapToDouble(Double::doubleValue).toArray();
                double corr = correlation.correlation(featureArray, labelArray);
                if (Double.isNaN(corr)) corr = 0.0;
                correlationResults.add(new String[]{feature, String.format(Locale.US, "%.4f", corr)});
            }

            correlationResults.sort((o1, o2) -> {
                double corr1 = Math.abs(Double.parseDouble(o1[1]));
                double corr2 = Math.abs(Double.parseDouble(o2[1]));
                return Double.compare(corr2, corr1);
            });

            saveResultsToCsv(outputDir, outputFileName, correlationResults);

        }
    }

    private static void saveResultsToCsv(String outputDir, String outputFileName, List<String[]> results) throws IOException {
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader("Feature", "SpearmanCorrelation")
                .build();

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath.resolve(outputFileName));
             CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat)) {
            csvPrinter.printRecords(results);
            LOGGER.info("Correlation CSV file saved to: " + outputPath.resolve(outputFileName));
        }
    }

}
