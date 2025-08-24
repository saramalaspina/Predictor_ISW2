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
import java.util.logging.Level;
import java.util.logging.Logger;

public class CorrelationCalculator {
    private static final Logger LOGGER = Logger.getLogger(CorrelationCalculator.class.getName());
    private static final String FEATURE_BUGGY = "Buggy";

    private static class ParsedData {
        final Map<String, List<Double>> featureValues;
        final List<Double> labelValues;

        public ParsedData(Map<String, List<Double>> featureValues, List<Double> labelValues) {
            this.featureValues = featureValues;
            this.labelValues = labelValues;
        }
    }

    private CorrelationCalculator() {}

    public static void calculateAndSave(String projectName) throws IOException {
        String inputFilePath = String.format("reportFiles/%s/Dataset.csv", projectName.toLowerCase());
        File inputFile = new File(inputFilePath);
        if (!inputFile.exists()) {
            LOGGER.severe("Dataset.csv not found at path: " + inputFilePath);
            return;
        }

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();

        ParsedData data;
        try (Reader reader = new FileReader(inputFile);
             CSVParser csvParser = CSVParser.parse(reader, csvFormat)) {
            data = parseDataset(csvParser);
        }

        if (data == null) {
            return;
        }

        SpearmansCorrelation correlation = new SpearmansCorrelation();
        List<String[]> correlationResults = new ArrayList<>();
        double[] labelArray = data.labelValues.stream().mapToDouble(Double::doubleValue).toArray();

        for (Map.Entry<String, List<Double>> entry : data.featureValues.entrySet()) {
            String feature = entry.getKey();
            double[] featureArray = entry.getValue().stream().mapToDouble(Double::doubleValue).toArray();

            double corr = correlation.correlation(featureArray, labelArray);
            if (Double.isNaN(corr)) {
                corr = 0.0;
            }
            correlationResults.add(new String[]{feature, String.format(Locale.US, "%.4f", corr)});
        }

        correlationResults.sort((o1, o2) -> {
            double corr1 = Math.abs(Double.parseDouble(o1[1]));
            double corr2 = Math.abs(Double.parseDouble(o2[1]));
            return Double.compare(corr2, corr1);
        });

        String outputDir = "reportFiles/" + projectName.toLowerCase() + "/";
        saveResultsToCsv(outputDir, "Correlation.csv", correlationResults);
    }

    private static ParsedData parseDataset(CSVParser csvParser) throws IOException {
        Map<String, Integer> headerMap = csvParser.getHeaderMap();
        if (headerMap == null || !headerMap.containsKey(FEATURE_BUGGY)) {
            LOGGER.severe("Could not read headers or 'Buggy' column not found in Dataset.csv");
            return null;
        }

        Map<String, List<Double>> featureValues = new HashMap<>();
        List<Double> labelValues = new ArrayList<>();
        final Set<String> nonNumericHeaders = Set.of(
                "FullyQualifiedName",
                FEATURE_BUGGY
        );

        List<String> numericColumns = new ArrayList<>();
        for (String header : headerMap.keySet()) {
            if (!nonNumericHeaders.contains(header)) {
                numericColumns.add(header);
                featureValues.put(header, new ArrayList<>());
            }
        }

        for (CSVRecord csvRecord : csvParser) {
            for (String feature : numericColumns) {
                featureValues.get(feature).add(parseDoubleOrDefault(csvRecord.get(feature), 0.0));
            }
            String label = csvRecord.get(FEATURE_BUGGY).trim().toLowerCase();
            labelValues.add("yes".equals(label) ? 1.0 : 0.0);
        }

        return new ParsedData(featureValues, labelValues);
    }

    private static double parseDoubleOrDefault(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException e) {
            return defaultValue;
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
            LOGGER.log(Level.INFO, "Correlation CSV file saved to: {0}", outputPath.resolve(outputFileName));
        }
    }
}