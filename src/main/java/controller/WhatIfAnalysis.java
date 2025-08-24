package controller;

import model.WekaClassifier;
import utils.PrintUtils;
import weka.classifiers.Classifier;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.CSVSaver;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WhatIfAnalysis {
    private static final Logger LOGGER = Logger.getLogger(WhatIfAnalysis.class.getName());

    private final String project;
    private final Instances datasetA; // Il dataset completo
    private final WekaClassifier bClassifierInfo; // Info sul miglior classificatore

    public WhatIfAnalysis(Instances fullDataset, WekaClassifier bestClassifier, String project) {
        this.datasetA = new Instances(fullDataset);
        this.bClassifierInfo = bestClassifier;
        this.project = project;
    }


    public void execute() throws Exception {
        LOGGER.info("--- Starting What-If Analysis ---");

        // --- Create datasets B+, C, e B ---
        LOGGER.info("Creating sub-datasets based on NSmells...");

        // --- B+: Portion of A with NSmells > 0
        Instances datasetBPlus = filterBySmell(this.datasetA, "greater");

        // --- C: Portion of A with NSmells = 0
        Instances datasetC = filterBySmell(this.datasetA, "equals");

        // --- B: Like B+ but with NSmells brought to  0
        Instances datasetB = new Instances(datasetBPlus);
        int nSmellsIndex = datasetB.attribute("NSmells").index();
        if (nSmellsIndex == -1) throw new IllegalStateException("Feature 'NSmells' not found.");
        datasetB.forEach(instance -> instance.setValue(nSmellsIndex, 0));

        // --- Train BClassifier on A (BClassifierA) ---
        LOGGER.info("Training BClassifier on the full dataset A...");
        Classifier bClassifierA = this.bClassifierInfo.getClassifier();
        bClassifierA.buildClassifier(this.datasetA);

        // --- Predict and count Actual/Estimated on A, B, B+, C ---
        LOGGER.info("Counting actual and estimated bugs on all datasets...");

        // Count "Actual" values
        int actualA = countActualBugs(this.datasetA);
        int actualBPlus = countActualBugs(datasetBPlus);
        int actualC = countActualBugs(datasetC);

        // Count "Estimated" values
        int estimatedA = countBuggyPredictions(bClassifierA, this.datasetA);
        int estimatedBPlus = countBuggyPredictions(bClassifierA, datasetBPlus);
        int estimatedC = countBuggyPredictions(bClassifierA, datasetC);
        int estimatedB = countBuggyPredictions(bClassifierA, datasetB);

        String outputDir = String.format("whatIfResults/%s/", this.project.toLowerCase());
        String outputFile = outputDir + "whatIf.csv";
        PrintUtils.printWhatIfResultsToCsv(outputFile,
                actualA, estimatedA,
                actualBPlus, estimatedBPlus,
                actualBPlus, estimatedB,
                actualC, estimatedC);

        saveDatasetToCsv(datasetB, outputDir, "DatasetB.csv");
        saveDatasetToCsv(datasetBPlus, outputDir, "DatasetBplus.csv");
        saveDatasetToCsv(datasetC, outputDir, "DatasetC.csv");

    }


    private Instances filterBySmell(Instances data, String comparison) {
        int attrIndex = data.attribute("NSmells").index();
        if (attrIndex == -1) {
            throw new IllegalArgumentException("Attribute not found: " + "NSmells");
        }

        Instances filteredData = new Instances(data, 0);

        for (int i = 0; i < data.numInstances(); i++) {
            Instance inst = data.instance(i);
            double currentValue = inst.value(attrIndex);
            boolean conditionMet = false;

            switch (comparison) {
                case "equals":
                    if (currentValue == 0) conditionMet = true;
                    break;
                case "greater":
                    if (currentValue > 0) conditionMet = true;
                    break;
                case "less":
                    if (currentValue < 0) conditionMet = true;
                    break;
                default:
                    throw new IllegalArgumentException("Comparison type not supported: " + comparison);
            }

            if (conditionMet) {
                filteredData.add(inst);
            }
        }
        return filteredData;
    }


    private int countBuggyPredictions(Classifier classifier, Instances data) throws Exception {
        if (data.isEmpty()) return 0;
        int buggyCount = 0;
        int buggyClassIndex = data.classAttribute().indexOfValue("yes");
        for (int i = 0; i < data.numInstances(); i++) {
            if (classifier.classifyInstance(data.instance(i)) == buggyClassIndex) {
                buggyCount++;
            }
        }
        return buggyCount;
    }

    private int countActualBugs(Instances data) {
        if (data.isEmpty()) return 0;
        int actualBuggyCount = 0;
        int buggyClassIndex = data.classAttribute().indexOfValue("yes");
        for (int i = 0; i < data.numInstances(); i++) {
            if (data.instance(i).classValue() == buggyClassIndex) {
                actualBuggyCount++;
            }
        }
        return actualBuggyCount;
    }

    private void saveDatasetToCsv(Instances data, String directoryPath, String fileName) {
        try {
            File dir = new File(directoryPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            CSVSaver saver = new CSVSaver();
            saver.setInstances(data);
            saver.setFile(new File(directoryPath + fileName));
            saver.writeBatch();
            LOGGER.log(Level.INFO, "Dataset {0} saved successfully to {1}", new Object[]{fileName, directoryPath});
        } catch (Exception e) {
            LOGGER.severe(String.format("Failed to save dataset %s. Error: %s", fileName, e.getMessage()));
        }
    }
}